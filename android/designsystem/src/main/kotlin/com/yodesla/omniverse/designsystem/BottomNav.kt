package com.yodesla.omniverse.designsystem

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Phone/tablet counterpart of [NavRail]: same [NavItem]s, touch bottom bar. */
@Composable
fun BottomNav(items: List<NavItem>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    NavigationBar(modifier, containerColor = c.background, contentColor = c.textSecondary) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.id == selectedId,
                onClick = { onSelect(item.id) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.accent,
                    selectedTextColor = c.accent,
                    unselectedIconColor = c.textSecondary,
                    unselectedTextColor = c.textSecondary,
                    indicatorColor = c.surface,
                ),
            )
        }
    }
}
