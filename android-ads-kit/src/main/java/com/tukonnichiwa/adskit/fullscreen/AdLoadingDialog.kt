package com.tukonnichiwa.adskit.fullscreen

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Internal full-screen wait surface shared by interstitial and rewarded requests. */
internal class AdLoadingDialog(activity: Activity, style: AdLoadingDialogStyle) {
    private val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(style.backgroundColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        content.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(style.indicatorColor)
        })
        style.message?.takeIf(String::isNotBlank)?.let { message ->
            content.addView(TextView(activity).apply {
                text = message
                gravity = Gravity.CENTER
                setTextColor(style.messageColor)
                textSize = style.messageSizeSp
                setPadding(24, 16, 24, 0)
            })
        }
        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun show() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Dialogs must be shown on the main thread" }
        if (!dialog.isShowing) dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (dialog.isShowing) dialog.dismiss()
        } else {
            dialog.window?.decorView?.post { if (dialog.isShowing) dialog.dismiss() }
        }
    }
}
