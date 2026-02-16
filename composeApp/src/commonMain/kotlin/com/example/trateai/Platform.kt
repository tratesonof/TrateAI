package com.example.trateai

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform