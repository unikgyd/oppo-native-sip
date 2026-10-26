package dev.mysip;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Optional UI extension for the tested ColorOS 13 dialer, enabled through LSPosed. */
public final class OppoDialpadHook implements IXposedHookLoadPackage {
    private static final String PHONE = "com.android.contacts";
    private static final String TAG = "mysip.native.dial";
    private static boolean errorLogged;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (Build.VERSION.SDK_INT != 33 || !PHONE.equals(load.packageName)
                || !PHONE.equals(load.processName)) return;
        try {
            XposedHelpers.findAndHookMethod("com.android.contacts.dialpad.view.DialpadBottomView",
                    load.classLoader, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try { install((View) param.thisObject); }
                    catch (Throwable e) { logOnce(e); }
                }
            });
            XposedBridge.log("MySIP: ColorOS 13 dialpad UI extension loaded");
        } catch (Throwable e) { logOnce(e); }
    }

    private static void logOnce(Throwable error) {
        if (!errorLogged) {
            errorLogged = true;
            XposedBridge.log("MySIP: dialpad extension unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static View named(View root, String id) {
        int value = root.getResources().getIdentifier(id, "id", PHONE);
        return value == 0 ? null : root.findViewById(value);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void install(View bottom) throws Exception {
        if (bottom.findViewWithTag(TAG) != null) return;
        Context context = bottom.getContext();
        // New OEM layouts must be checked before activation; fail without changing them.
        if (!"13.20.0".equals(context.getPackageManager().getPackageInfo(PHONE, 0).versionName)) return;
        View root = bottom.getRootView();
        View rowView = named(bottom, "dual_sim_view");
        View first = named(bottom, "sim1_dial_btn");
        View second = named(bottom, "sim2_dial_btn");
        View digitsView = named(root, "normal_input");
        if (!(rowView instanceof LinearLayout) || !(digitsView instanceof TextView)
                || first == null || second == null || rowView.findViewWithTag(TAG) != null) return;
        LinearLayout row = (LinearLayout) rowView;
        if (first.getParent() != row || second.getParent() != row
                || row.getOrientation() != LinearLayout.HORIZONTAL) return;
        // The OEM spacer also has weight; leaving it weighted takes most of
        // the row after the two original fixed-width buttons become weighted.
        row.setWeightSum(3f);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child == first || child == second) continue;
            LinearLayout.LayoutParams spacer = (LinearLayout.LayoutParams) child.getLayoutParams();
            spacer.width = dp(context, 6);
            spacer.weight = 0;
            child.setLayoutParams(spacer);
        }
        TextView digits = (TextView) digitsView;
        TextView button = new TextView(context);
        button.setTag(TAG);
        button.setText("SIP");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("通过 MySIP 拨打");
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(45, 99, 222));
        background.setCornerRadius(dp(context, 24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), background, null));
        for (View sim : new View[]{first, second}) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) sim.getLayoutParams();
            lp.width = 0; lp.weight = 1;
            sim.setLayoutParams(lp);
        }
        for (String name : new String[]{"sim1_name", "sim2_name"}) {
            View text = named(row, name);
            if (text instanceof TextView) {
                ((TextView) text).setTextSize(11);
                ((TextView) text).setSingleLine(true);
                ((TextView) text).setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMarginStart(dp(context, 6));
        row.addView(button, lp);
        Runnable update = () -> {
            boolean enabled = digits.getText().length() > 0;
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.45f);
        };
        digits.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        update.run();
        button.setOnClickListener(view -> {
            try {
                String number = PhoneNumberUtils.stripSeparators(digits.getText().toString().trim());
                if (number.isEmpty()) return;
                if (PhoneNumberUtils.isEmergencyNumber(number)) {
                    Toast.makeText(context, "紧急电话请使用卡 1 或卡 2", Toast.LENGTH_LONG).show();
                    return;
                }
                TelecomManager telecom = context.getSystemService(TelecomManager.class);
                PhoneAccountHandle account = new PhoneAccountHandle(
                        new ComponentName("dev.mysip", "dev.mysip.SipConnectionService"), "mysip-primary-v1");
                if (telecom == null || !telecom.getCallCapablePhoneAccounts().contains(account)) {
                    Toast.makeText(context, "请先在系统设置启用 MySIP 通话账户", Toast.LENGTH_LONG).show();
                    return;
                }
                Bundle extras = new Bundle();
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
                telecom.placeCall(Uri.fromParts("tel", number, null), extras);
            } catch (Exception e) {
                Toast.makeText(context, "无法通过 MySIP 拨打，请检查通话账户", Toast.LENGTH_LONG).show();
            }
        });
    }
}
