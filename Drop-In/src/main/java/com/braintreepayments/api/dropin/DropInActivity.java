package com.braintreepayments.api.dropin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.LinearSnapHelper;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.braintreepayments.api.AndroidPay;
import com.braintreepayments.api.BraintreeFragment;
import com.braintreepayments.api.DataCollector;
import com.braintreepayments.api.PayPal;
import com.braintreepayments.api.PaymentMethod;
import com.braintreepayments.api.Venmo;
import com.braintreepayments.api.dropin.adapters.SupportedPaymentMethodsAdapter;
import com.braintreepayments.api.dropin.adapters.SupportedPaymentMethodsAdapter.PaymentMethodSelectedListener;
import com.braintreepayments.api.dropin.adapters.VaultedPaymentMethodsAdapter;
import com.braintreepayments.api.dropin.interfaces.AnimationFinishedListener;
import com.braintreepayments.api.dropin.utils.PaymentMethodType;
import com.braintreepayments.api.exceptions.AuthenticationException;
import com.braintreepayments.api.exceptions.AuthorizationException;
import com.braintreepayments.api.exceptions.ConfigurationException;
import com.braintreepayments.api.exceptions.DownForMaintenanceException;
import com.braintreepayments.api.exceptions.InvalidArgumentException;
import com.braintreepayments.api.exceptions.ServerException;
import com.braintreepayments.api.exceptions.UnexpectedException;
import com.braintreepayments.api.exceptions.UpgradeRequiredException;
import com.braintreepayments.api.interfaces.BraintreeCancelListener;
import com.braintreepayments.api.interfaces.BraintreeErrorListener;
import com.braintreepayments.api.interfaces.BraintreeResponseListener;
import com.braintreepayments.api.interfaces.ConfigurationListener;
import com.braintreepayments.api.interfaces.PaymentMethodNonceCreatedListener;
import com.braintreepayments.api.interfaces.PaymentMethodNoncesUpdatedListener;
import com.braintreepayments.api.models.Authorization;
import com.braintreepayments.api.models.ClientToken;
import com.braintreepayments.api.models.Configuration;
import com.braintreepayments.api.models.PaymentMethodNonce;

import java.util.List;

import static android.view.animation.AnimationUtils.loadAnimation;

public class DropInActivity extends Activity implements ConfigurationListener, BraintreeCancelListener,
        BraintreeErrorListener, PaymentMethodSelectedListener, PaymentMethodNoncesUpdatedListener,
        PaymentMethodNonceCreatedListener {

    /**
     * Error messages are returned as the value of this key in the data intent in {@link
     * android.app.Activity#onActivityResult(int, int, android.content.Intent)} if {@code
     * responseCode} is not {@link android.app.Activity#RESULT_OK} or {@link
     * android.app.Activity#RESULT_CANCELED}
     */
    public static final String EXTRA_ERROR_MESSAGE =
            "com.braintreepayments.api.dropin.EXTRA_ERROR_MESSAGE";

    /**
     * The payment method flow halted due to a resolvable error (authentication, authorization, SDK
     * upgrade required).
     */
    public static final int BRAINTREE_RESULT_DEVELOPER_ERROR = 2;

    /**
     * The payment method flow halted due to an error from the Braintree gateway. The best recovery
     * path is to try again with a new authorization.
     */
    public static final int BRAINTREE_RESULT_SERVER_ERROR = 3;

    /**
     * The payment method flow halted due to the Braintree gateway going down for maintenance. Try
     * again later.
     */
    public static final int BRAINTREE_RESULT_SERVER_UNAVAILABLE = 4;

    private static final int ADD_CARD_REQUEST_CODE = 1;
    private static final String EXTRA_SHEET_SLIDE_UP_PERFORMED = "com.braintreepayments.api.EXTRA_SHEET_SLIDE_UP_PERFORMED";
    private static final String EXTRA_DEVICE_DATA = "com.braintreepayments.api.EXTRA_DEVICE_DATA";

    @VisibleForTesting
    protected PaymentRequest mPaymentRequest;

    private BraintreeFragment mBraintreeFragment;
    private String mDeviceData;

    private View mBottomSheet;
    private ViewSwitcher mLoadingViewSwitcher;
    private TextView mAvailablePaymentMethodsHeader;
    private ListView mAvailablePaymentMethodListView;
    private View mVaultedPaymentMethodsContainer;
    private RecyclerView mVaultedPaymentMethodsView;

    private boolean mSheetSlideUpPerformed;
    private boolean mSheetSlideDownPerformed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bt_drop_in_activity);

        mPaymentRequest = getIntent().getParcelableExtra(PaymentRequest.EXTRA_CHECKOUT_REQUEST);
        mBottomSheet = findViewById(R.id.bt_dropin_bottom_sheet);
        mLoadingViewSwitcher = (ViewSwitcher) findViewById(R.id.bt_loading_view_switcher);
        mAvailablePaymentMethodsHeader = (TextView) findViewById(R.id.bt_available_payment_methods_header);
        mAvailablePaymentMethodListView = (ListView) findViewById(R.id.bt_available_payment_methods);
        mVaultedPaymentMethodsContainer = findViewById(R.id.bt_vaulted_payment_methods_wrapper);
        mVaultedPaymentMethodsView = (RecyclerView) findViewById(R.id.bt_vaulted_payment_methods);
        mVaultedPaymentMethodsView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        new LinearSnapHelper().attachToRecyclerView(mVaultedPaymentMethodsView);

        try {
            mBraintreeFragment = getBraintreeFragment();

            if (Authorization.fromString(mPaymentRequest.getAuthorization()) instanceof ClientToken
                    && !mBraintreeFragment.hasFetchedPaymentMethodNonces()) {
                PaymentMethod.getPaymentMethodNonces(mBraintreeFragment, true);
            } else if (mBraintreeFragment.hasFetchedPaymentMethodNonces()) {
                onPaymentMethodNoncesUpdated(mBraintreeFragment.getCachedPaymentMethodNonces());
            }
        } catch (InvalidArgumentException e) {
            Intent intent = new Intent()
                    .putExtra(EXTRA_ERROR_MESSAGE, e.getMessage());
            setResult(BRAINTREE_RESULT_DEVELOPER_ERROR, intent);
            finish();
            return;
        }

        if (savedInstanceState != null) {
            mSheetSlideUpPerformed = savedInstanceState.getBoolean(EXTRA_SHEET_SLIDE_UP_PERFORMED,
                    false);
            mDeviceData = savedInstanceState.getString(EXTRA_DEVICE_DATA);
        }

        slideUp();
    }

    @VisibleForTesting
    protected BraintreeFragment getBraintreeFragment() throws InvalidArgumentException {
        if (TextUtils.isEmpty(mPaymentRequest.getAuthorization())) {
            throw new InvalidArgumentException("A client token or client key must be specified " +
                    "in the " + PaymentRequest.class.getSimpleName());
        }

        return BraintreeFragment.newInstance(this, mPaymentRequest.getAuthorization());
    }

    @Override
    public void onConfigurationFetched(final Configuration configuration) {
        if (mPaymentRequest.shouldCollectDeviceData() && TextUtils.isEmpty(mDeviceData)) {
            DataCollector.collectDeviceData(mBraintreeFragment, new BraintreeResponseListener<String>() {
                @Override
                public void onResponse(String deviceData) {
                    mDeviceData = deviceData;
                }
            });
        }

        AndroidPay.isReadyToPay(mBraintreeFragment, new BraintreeResponseListener<Boolean>() {
            @Override
            public void onResponse(Boolean isReadyToPay) {
                mAvailablePaymentMethodListView.setAdapter(
                        new SupportedPaymentMethodsAdapter(DropInActivity.this,
                                configuration, isReadyToPay, DropInActivity.this));
                mLoadingViewSwitcher.setDisplayedChild(1);
            }
        });
    }

    @Override
    public void onCancel(int requestCode) {
        mLoadingViewSwitcher.setDisplayedChild(1);
    }

    @Override
    public void onError(final Exception error) {
        slideDown(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                if (error instanceof AuthenticationException || error instanceof AuthorizationException ||
                        error instanceof UpgradeRequiredException) {
                    mBraintreeFragment.sendAnalyticsEvent("sdk.exit.developer-error");
                    setResult(BRAINTREE_RESULT_DEVELOPER_ERROR, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
                } else if (error instanceof ConfigurationException) {
                    mBraintreeFragment.sendAnalyticsEvent("sdk.exit.configuration-exception");
                    setResult(BRAINTREE_RESULT_SERVER_ERROR, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
                } else if (error instanceof ServerException || error instanceof UnexpectedException) {
                    mBraintreeFragment.sendAnalyticsEvent("sdk.exit.server-error");
                    setResult(BRAINTREE_RESULT_SERVER_ERROR, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
                } else if (error instanceof DownForMaintenanceException) {
                    mBraintreeFragment.sendAnalyticsEvent("sdk.exit.server-unavailable");
                    setResult(BRAINTREE_RESULT_SERVER_UNAVAILABLE, new Intent().putExtra(EXTRA_ERROR_MESSAGE, error));
                }

                finish();
            }
        });
    }

    @Override
    public void onPaymentMethodNonceCreated(final PaymentMethodNonce paymentMethodNonce) {
        slideDown(new AnimationFinishedListener() {
            @Override
            public void onAnimationFinished() {
                DropInResult.setLastUsedPaymentMethodType(DropInActivity.this, paymentMethodNonce);

                DropInResult result = new DropInResult()
                        .paymentMethodNonce(paymentMethodNonce)
                        .deviceData(mDeviceData);
                Intent intent = new Intent().putExtra(DropInResult.EXTRA_DROP_IN_RESULT, result);

                setResult(RESULT_OK, intent);
                finish();
            }
        });
    }

    @Override
    public void onPaymentMethodSelected(PaymentMethodType type) {
        mLoadingViewSwitcher.setDisplayedChild(0);

        switch (type) {
            case PAYPAL:
                PayPal.authorizeAccount(mBraintreeFragment);
                break;
            case ANDROID_PAY:
                AndroidPay.requestAndroidPay(mBraintreeFragment, mPaymentRequest.getAndroidPayCart(),
                        mPaymentRequest.isAndroidPayShippingAddressRequired(),
                        mPaymentRequest.isAndroidPayPhoneNumberRequired(),
                        mPaymentRequest.getAndroidPayAllowedCountriesForShipping());
                break;
            case PAY_WITH_VENMO:
                Venmo.authorizeAccount(mBraintreeFragment);
                break;
            case UNKNOWN:
                Intent intent = new Intent(this, AddCardActivity.class)
                        .putExtra(PaymentRequest.EXTRA_CHECKOUT_REQUEST, mPaymentRequest);
                startActivityForResult(intent, ADD_CARD_REQUEST_CODE);
                break;
        }
    }

    @Override
    public void onPaymentMethodNoncesUpdated(final List<PaymentMethodNonce> paymentMethodNonces) {
        if (paymentMethodNonces.size() > 0) {
            mAvailablePaymentMethodsHeader.setText(R.string.bt_other);
            mVaultedPaymentMethodsContainer.setVisibility(View.VISIBLE);
            mVaultedPaymentMethodsView.setAdapter(new VaultedPaymentMethodsAdapter(this, paymentMethodNonces));
        } else {
            mAvailablePaymentMethodsHeader.setText(R.string.bt_select_payment_method);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_SHEET_SLIDE_UP_PERFORMED, mSheetSlideUpPerformed);
        outState.putString(EXTRA_DEVICE_DATA, mDeviceData);
    }

    @Override
    protected void onActivityResult(int requestCode, final int resultCode, Intent data) {
        mLoadingViewSwitcher.setDisplayedChild(0);

        if (resultCode == Activity.RESULT_CANCELED) {
            mLoadingViewSwitcher.setDisplayedChild(1);
        } else if (requestCode == ADD_CARD_REQUEST_CODE) {
            final Intent response;
            if (resultCode == Activity.RESULT_OK) {
                DropInResult result = data.getParcelableExtra(DropInResult.EXTRA_DROP_IN_RESULT);
                DropInResult.setLastUsedPaymentMethodType(this, result.getPaymentMethodNonce());

                result.deviceData(mDeviceData);
                response = new Intent()
                        .putExtra(DropInResult.EXTRA_DROP_IN_RESULT, result);
            } else {
                response = data;
            }

            slideDown(new AnimationFinishedListener() {
                @Override
                public void onAnimationFinished() {
                    setResult(resultCode, response);
                    finish();
                }
            });
        }
    }

    public void onBackgroundClicked(View v) {
        onBackPressed();
    }

    @Override
    public void onBackPressed() {
        if (!mSheetSlideDownPerformed) {
            mSheetSlideDownPerformed = true;
            slideDown(new AnimationFinishedListener() {
                @Override
                public void onAnimationFinished() {
                    finish();
                }
            });
        }
    }

    private void slideUp() {
        if (!mSheetSlideUpPerformed) {
            mSheetSlideUpPerformed = true;
            mBottomSheet.startAnimation(loadAnimation(this, R.anim.bt_slide_in_up));
        }
    }

    private void slideDown(final AnimationFinishedListener listener) {
        Animation slideOutAnimation = loadAnimation(this, R.anim.bt_slide_out_down);
        slideOutAnimation.setFillAfter(true);
        if (listener != null) {
            slideOutAnimation.setAnimationListener(new AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    listener.onAnimationFinished();
                }

                @Override
                public void onAnimationRepeat(Animation animation) {

                }
            });
        }
        mBottomSheet.startAnimation(slideOutAnimation);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
