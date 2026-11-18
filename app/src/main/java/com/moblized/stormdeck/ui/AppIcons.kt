package com.moblized.stormdeck.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The hurricane symbol used across the app (tab bar, car, notifications, launcher). */
object AppIcons {
    const val RING = "M12,7.6A4.4,4.4 0 1,1 11.99,7.6ZM12,9.9A2.1,2.1 0 1,0 12.01,9.9Z"
    const val ARM_TOP = "M11.2,7.7C12.3,4.4 15.9,2.3 20.8,2.4C17.8,3.6 15.6,5.9 15.2,9.2Z"
    const val ARM_BOTTOM = "M12.8,16.3C11.7,19.6 8.1,21.7 3.2,21.6C6.2,20.4 8.4,18.1 8.8,14.8Z"

    val Hurricane: ImageVector by lazy {
        ImageVector.Builder(name = "Hurricane", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(RING), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .addPath(pathData = addPathNodes(ARM_TOP), fill = SolidColor(Color.Black))
            .addPath(pathData = addPathNodes(ARM_BOTTOM), fill = SolidColor(Color.Black))
            .build()
    }
}
