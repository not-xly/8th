package com.xly.u_8th.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class EighthCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if (com.xly.u_8th.BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHost("com.google.android.projection.gearhead", "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf")
                .addAllowedHost("com.google.android.embedded.projection", "0264238559a79ad336d045a1fa62d0bbdd82c2dd1ea0d14db7e3ed0b555a4ea")
                .addAllowedHost("com.google.android.projection.gearhead.test", "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf")
                .addAllowedHost("com.google.android.apps.automotive.templates.host", "1151aad4a77d7a24a9e61222b5ffc720bf21af2d4d54dc439e3080d90e540f4ea")
                .build()
        }
    }

    override fun onCreateSession(): Session = EighthSession()
}