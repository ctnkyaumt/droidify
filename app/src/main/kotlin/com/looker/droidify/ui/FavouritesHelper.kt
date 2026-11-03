package com.looker.droidify.ui

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.looker.droidify.R
import com.looker.droidify.database.Database
import kotlinx.coroutines.launch

fun Fragment.markInstalledAsFavourites(
    anchorView: View,
    onAddFavourites: (Collection<String>) -> Unit,
) {
    viewLifecycleOwner.lifecycleScope.launch {
        val installed = Database.ProductAdapter.getInstalledPackageNames()
        onAddFavourites(installed)
        Snackbar.make(
            anchorView,
            R.string.marked_installed_as_favourites,
            Snackbar.LENGTH_SHORT,
        ).show()
    }
}
