package com.andaxin.data.capture

/**
 * 登录成功拿到的那把票。原来长在 AuthFeed.kt 里，随 SessionStore 一起搬进共享层
 * （SessionStore.save 的入参就是它）。字段取证见 AuthFeed 顶上那段 —— snake_case 的
 * 是响应原文，这里已经按驼峰收好。
 */
data class TokenBundle(
    val accessToken: String,
    val refreshToken: String,
    val userCode: String,
    val userName: String,
    val userType: String,
    val expiresInSeconds: Long,
)
