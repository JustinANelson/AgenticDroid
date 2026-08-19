package com.justnels.agenticdroid

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class AgenticDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Register Bouncy Castle provider for modern SSH algorithms (like X25519)
        // We remove any existing provider with the same name to avoid collisions
        // with the restricted system version of BC.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
