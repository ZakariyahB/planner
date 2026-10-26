package app.planner

import android.app.Application
import app.planner.routines.RoutineBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PlannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RoutineBootstrap.start(this)
    }
}
