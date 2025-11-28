package com.futaiii.sudodroid

import android.app.Application
import com.futaiii.sudodroid.data.NodeRepository

class SudodroidApp : Application() {
    lateinit var nodeRepository: NodeRepository
        private set

    override fun onCreate() {
        super.onCreate()
        nodeRepository = NodeRepository(this)
    }
}
