package com.futaiii.sudodroid

import android.app.Application
import com.futaiii.sudodroid.data.NodeRepository
import com.futaiii.sudodroid.net.GoCoreClient

class SudodroidApp : Application() {
    lateinit var nodeRepository: NodeRepository
        private set

    override fun onCreate() {
        super.onCreate()
        GoCoreClient.initialize(cacheDir)
        nodeRepository = NodeRepository(this)
    }
}
