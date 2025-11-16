/*
 * This file is part of Blokada.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright © 2022 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package service

import com.android.billingclient.api.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import model.*
import utils.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BillingService: IPaymentService {

    private val context by lazy { Services.context }

    private lateinit var client: BillingClient
    private var connected = false
        @Synchronized set
        @Synchronized get

    private var latestProductList: List<ProductDetails> = emptyList()
        @Synchronized set
        @Synchronized get

    private var ongoingPurchase: Pair<ProductId, CancellableContinuation<PaymentPayload>>? = null
        @Synchronized set
        @Synchronized get

    override suspend fun setup() {
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()
        client = BillingClient.newBuilder(context.requireAppContext())
            .setListener(purchaseListener)
            .enablePendingPurchases(pendingPurchasesParams)
            .build()
    }

    private suspend fun getConnectedClient(): BillingClient {
        if (connected) return client
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {

                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    when (billingResult.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            connected = true
                            cont.resume(client)
                        }
                        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                            connected = false
                            cont.resumeWithException(NoPayments())
                        }
                        else -> {
                            connected = false
                            cont.resumeWithException(
                                BlokadaException(
                                    "onBillingSetupFinished returned wrong result: $billingResult"
                                )
                            )
                        }
                    }
                }

                // Not sure if this is ever called as a result of startConnection or only later
                override fun onBillingServiceDisconnected() {
                    connected = false
                    if (!cont.isCompleted)
                        cont.resumeWithException(BlokadaException("onBillingServiceDisconnected"))
                }

            })
        }
    }

    override suspend fun refreshProducts(): List<Product> {
        val ids = listOf("cloud_12month", "plus_month", "plus_12month")

        val params = QueryProductDetailsParams.newBuilder()
        params.setProductList(ids.map {
            QueryProductDetailsParams.Product.newBuilder()
            .setProductId(it)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        })

        // leverage queryProductDetails Kotlin extension function
        val productDetailsResult = withContext(Dispatchers.IO) {
            getConnectedClient().queryProductDetails(params.build())
        }

        latestProductList = productDetailsResult.productDetailsList ?: emptyList()
        val payments = productDetailsResult.productDetailsList?.mapNotNull { productDetails ->
            val offer = productDetails.subscriptionOfferDetails?.first()
            val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0 }

            if (offer == null || phase == null) {
                null
            } else {
                Product(
                    id = productDetails.productId,
                    title = productDetails.title,
                    description = productDetails.description,
                    price = getPriceString(phase),
                    pricePerMonth = getPricePerMonthString(phase),
                    periodMonths = getPeriodMonths(phase),
                    type = if(productDetails.productId.startsWith("cloud")) "cloud" else "plus",
                    trial = getTrial(offer)
                )
            }
        }?.sortedBy { it.periodMonths } ?: emptyList()
        if (payments.isEmpty()) throw NoPayments()
        return payments
    }

    private val purchaseListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    ongoingPurchase?.let { c ->
                        val (productId, cont) = c
                        val purchase = purchases
                            .asSequence()
                            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                            .firstOrNull { it.products.any { p -> p == productId } }

                        if (purchase == null) {
                            cont.resumeWithException(NoRelevantPurchase())
                        } else {
                            cont.resume(
                                PaymentPayload(
                                purchase_token = purchase.purchaseToken,
                                subscription_id = productId,
                                user_initiated = true
                            )
                            )
                        }
                    } ?: run {
                        Logger.w("Billing", "There was no ongoing purchase")
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // Handle an error caused by a user cancelling the purchase flow.
                Logger.v("Billing", "buyProduct: User cancelled purchase")
                ongoingPurchase?.second?.resumeWithException(UserCancelledException())
            }
            else -> {
                // Handle any other error codes.
                Logger.w("Billing", "buyProduct: Purchase error: $billingResult")
                val exception = if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    AlreadyPurchasedException()
                } else BlokadaException("Purchase error: $billingResult")

                ongoingPurchase?.second?.resumeWithException(exception)
            }
        }
        ongoingPurchase = null
    }

    override suspend fun getActivePurchase(): ProductId? {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val purchasesResult = getConnectedClient().queryPurchasesAsync(params)
        return if (purchasesResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            purchasesResult.purchasesList
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .maxByOrNull { it.purchaseTime }?.products?.firstOrNull()
        } else {
            Logger.w("Billing", "Failed refreshing purchases, response code not OK: ${purchasesResult.billingResult}")
            null
        }
    }

    override suspend fun buyProduct(id: ProductId): PaymentPayload {
        if (runIgnoringException({ restorePurchase() }, otherwise = emptyList()).isNotEmpty())
            throw AlreadyPurchasedException()

        val details = latestProductList.firstOrNull { it.productId == id } ?:
            throw BlokadaException("Unknown product ID")
        val offerToken = details.subscriptionOfferDetails!!.first().offerToken

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()))
            .build()
        val activity = context.requireActivity()
        val responseCode = getConnectedClient().launchBillingFlow(activity, flowParams).responseCode

        if (responseCode != BillingClient.BillingResponseCode.OK) {
            throw BlokadaException("buyProduct: error $responseCode")
        }

        return suspendCancellableCoroutine { cont ->
            ongoingPurchase = id to cont
        }
    }

    override suspend fun restorePurchase(): List<PaymentPayload> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val purchasesResult = getConnectedClient().queryPurchasesAsync(params)

        if (purchasesResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val successfulPurchases = purchasesResult.purchasesList
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }

            if (successfulPurchases.isNotEmpty()) {
                Logger.v("Billing", "restore: Restoring ${successfulPurchases.size} purchases")
                return successfulPurchases.map {
                    PaymentPayload(
                        purchase_token = it.purchaseToken,
                        subscription_id = it.products.first(),
                        user_initiated = false
                    )
                }
            } else {
                throw BlokadaException("Restoring purchase found no successful purchases")
            }
        } else {
            throw BlokadaException("Restoring purchase error: ${purchasesResult.billingResult}")
        }
    }

    override suspend fun changeProduct(id: ProductId): PaymentPayload {
        val details = latestProductList.firstOrNull { it.productId == id } ?:
            throw BlokadaException("Unknown product ID")
        val offerToken = details.subscriptionOfferDetails!!.first().offerToken

        val queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val purchasesResult = getConnectedClient().queryPurchasesAsync(queryPurchasesParams)

        val existingPurchase = if (purchasesResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            purchasesResult.purchasesList
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .maxByOrNull { it.purchaseTime }
        } else {
            null
        }

        if (existingPurchase == null) {
            throw BlokadaException("changeProduct: no existing purchase")
        }

        val existingId = existingPurchase.products.first()

        val replacementMode = when (existingId) {
            "cloud_12month" -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION
            "plus_1month" -> if (id == "plus_12month") BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION else BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
            else -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .setSubscriptionProductReplacementParams(
                BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.newBuilder()
                    .setOldProductId(existingId)
                    .setReplacementMode(replacementMode)
                    .build()
            )
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val activity = context.requireActivity()
        val responseCode = getConnectedClient().launchBillingFlow(activity, flowParams).responseCode

        if (responseCode != BillingClient.BillingResponseCode.OK) {
            throw BlokadaException("changeProduct: error $responseCode")
        }

        return suspendCancellableCoroutine { cont ->
            ongoingPurchase = id to cont
        }
    }

    private fun getTrial(it: ProductDetails.SubscriptionOfferDetails): Boolean {
        return it.offerTags.contains("free7")
    }

    private fun getPeriodMonths(it: ProductDetails.PricingPhase): Int {
        return if (it.billingPeriod == "P1Y") 12 else 1
    }

    private fun getPricePerMonthString(it: ProductDetails.PricingPhase): String {
        val periodMonths = getPeriodMonths(it)
        val price = it.priceAmountMicros
        val perMonth = price / periodMonths
        return priceFormat.format(perMonth / 1_000_000f, it.priceCurrencyCode)
    }

    private fun getPriceString(it: ProductDetails.PricingPhase): String {
        return priceFormat.format(it.priceAmountMicros / 1_000_000f, it.priceCurrencyCode)
    }

    private val priceFormat = "%.2f %s"
}
