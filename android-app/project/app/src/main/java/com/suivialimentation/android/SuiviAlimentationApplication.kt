package com.suivialimentation.android

import android.app.Application
import com.suivialimentation.android.di.AppContainer

class SuiviAlimentationApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
