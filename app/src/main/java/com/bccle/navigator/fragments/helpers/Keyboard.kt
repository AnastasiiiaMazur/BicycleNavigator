package com.bccle.navigator.fragments.helpers

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.children
import androidx.fragment.app.Fragment

fun Fragment.hideKeyboard() {
    view?.hideKeyboard()
}

fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
    clearFocus()
}

fun View.installHideKeyboardOnTouchOutside(skip: (View) -> Boolean = { it is EditText }) {
    if (!skip(this)) {
        setOnTouchListener { v, _ ->
            v.hideKeyboard()
            false // don't consume; let normal clicks/scroll happen
        }
    }
    if (this is ViewGroup) {
        children.forEach { child -> child.installHideKeyboardOnTouchOutside(skip) }
    }
}