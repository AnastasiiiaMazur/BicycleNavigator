package com.bccle.navigator

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.fragment.NavHostFragment
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout

    private lateinit var pageTitle: TextView
    private lateinit var backButton: ImageView
    private lateinit var settingsButton: ImageView
    private lateinit var newRouteButton: ImageView
    private lateinit var savedRoutesButton: ImageView
    private lateinit var homeButton: ImageView
    private lateinit var statsButton: ImageView
    private lateinit var manualRouteButton: ImageView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.main)
        pageTitle = findViewById(R.id.pageTitle)
        backButton = findViewById(R.id.backButton)
        settingsButton = findViewById(R.id.settingsButton)
        newRouteButton = findViewById(R.id.newRouteButton)
        savedRoutesButton = findViewById(R.id.savedRoutesButton)
        homeButton = findViewById(R.id.homeButton)
        statsButton = findViewById(R.id.statsButton)
        manualRouteButton = findViewById(R.id.manualRouteButton)

        applyFullscreen(root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        navController.addOnDestinationChangedListener { _, destination, _ ->
            changeTitle(destination.id)
        }

        homeButton.setOnClickListener    { navController.navigate(R.id.homeFragment) }
        newRouteButton.setOnClickListener  { navController.navigate(R.id.newRouteFragment) }
        savedRoutesButton.setOnClickListener   { navController.navigate(R.id.savedRoutesFragment) }
        statsButton.setOnClickListener    { navController.navigate(R.id.statsFragment) }
        manualRouteButton.setOnClickListener  { navController.navigate(R.id.manualRouteFragment) }
        settingsButton.setOnClickListener { navController.navigate(R.id.settingsFragment) }

        backButton.setOnClickListener {
            val currentDestination = navHostFragment.navController.currentDestination?.id

            if (currentDestination == R.id.homeFragment) {
                finish()
            } else if (currentDestination == R.id.settingsFragment) {
                navHostFragment.navController.popBackStack()
            } else {
                navHostFragment.navController.navigate(R.id.homeFragment)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            val currentDestination = navHostFragment.navController.currentDestination?.id

            if (currentDestination == R.id.homeFragment) {
                finish()
            } else if (currentDestination == R.id.settingsFragment) {
                navHostFragment.navController.popBackStack()
            } else {
                navHostFragment.navController.navigate(R.id.homeFragment)
            }
        }
    }

    private fun changeTitle(@IdRes destId: Int) {
        when (destId) {
            R.id.homeFragment -> { pageTitle.text = "HOME" }
            R.id.newRouteFragment -> { pageTitle.text = "TRIP GENERATOR" }
            R.id.savedRoutesFragment -> { pageTitle.text = "RIDE GALLERY" }
            R.id.statsFragment -> { pageTitle.text = "STATISTICS" }
            R.id.manualRouteFragment -> { pageTitle.text = "CREATE MANUAL RIDE" }
            R.id.settingsFragment -> { pageTitle.text = "SETTINGS" }
            else -> {
                pageTitle.text = ""
            }
        }
    }
}