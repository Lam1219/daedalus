package com.medicina.daedaluscompanion.utils

import android.app.Application
import com.medicina.daedaluscompanion.ble.ChestStrapBLEManager
import com.medicina.daedaluscompanion.wear.WatchDataClient

/**
 * Application-level device manager.
 * Holds single instances of BLE and Wear OS clients.
 */
class DaedalusApplication : Application() {
    
    lateinit var chestStrapBLE: ChestStrapBLEManager
        private set
    
    lateinit var watchClient: WatchDataClient
        private set
    
    override fun onCreate() {
        super.onCreate()
        chestStrapBLE = ChestStrapBLEManager(this)
        watchClient = WatchDataClient(this)
    }
}
