package com.gcc.app.ui.utils

import com.gcc.shared.model.SpotTag

fun SpotTag.displayLabel(): String = when (this) {
    SpotTag.TRASH -> "Trash"
    SpotTag.LEAVES -> "Leaves"
    SpotTag.GLASS -> "Glass"
    SpotTag.METAL -> "Metal"
    SpotTag.TIRES -> "Tires"
    SpotTag.FURNITURE -> "Furniture"
    SpotTag.CONSTRUCTION -> "Construction"
    SpotTag.ORGANIC -> "Organic"
    SpotTag.HAZARDOUS -> "Hazardous"
    SpotTag.ELECTRONICS -> "Electronics"
    SpotTag.CLOTHING -> "Clothing"
    SpotTag.OTHER -> "Other"
}

fun SpotTag.emoji(): String = when (this) {
    SpotTag.TRASH -> "\uD83D\uDDD1\uFE0F"
    SpotTag.LEAVES -> "\uD83C\uDF42"
    SpotTag.GLASS -> "\uD83E\uDDA0"
    SpotTag.METAL -> "\u26D3\uFE0F"
    SpotTag.TIRES -> "\uD83D\uDEDE"
    SpotTag.FURNITURE -> "\uD83E\uDE91"
    SpotTag.CONSTRUCTION -> "\uD83D\uDEA7"
    SpotTag.ORGANIC -> "\uD83C\uDF3F"
    SpotTag.HAZARDOUS -> "\u2622\uFE0F"
    SpotTag.ELECTRONICS -> "\uD83D\uDD0C"
    SpotTag.CLOTHING -> "\uD83D\uDC55"
    SpotTag.OTHER -> "\uD83D\uDCE6"
}
