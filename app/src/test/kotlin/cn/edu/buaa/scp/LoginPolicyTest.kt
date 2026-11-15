package cn.edu.buaa.scp

import cn.edu.buaa.scp.store.LoginPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「记住密码 / 自动登录」判定规则测试。 */
class LoginPolicyTest {

    @Test
    fun autoLoginNeedsAllConditions() {
        assertTrue(
            LoginPolicy.canAutoLogin(
                rememberPassword = true,
                autoLogin = true,
                account = "20250001",
                password = "secret",
            ),
        )
        assertFalse(LoginPolicy.canAutoLogin(false, true, "20250001", "secret"))
        assertFalse(LoginPolicy.canAutoLogin(true, false, "20250001", "secret"))
        assertFalse(LoginPolicy.canAutoLogin(true, true, "  ", "secret"))
        assertFalse(LoginPolicy.canAutoLogin(true, true, "20250001", null))
        assertFalse(LoginPolicy.canAutoLogin(true, true, "20250001", ""))
    }

    @Test
    fun detectsRejectedPasswordMessages() {
        assertTrue(LoginPolicy.passwordRejected("学号或密码错误（HTTP 401）"))
        assertTrue(LoginPolicy.passwordRejected("用户名或密码错误。"))
        assertTrue(LoginPolicy.passwordRejected("账号或密码错误"))
        assertTrue(LoginPolicy.passwordRejected("密码不正确"))
        assertFalse(LoginPolicy.passwordRejected(""))
        assertFalse(LoginPolicy.passwordRejected("登录尝试过多，账号已被临时锁定，请稍后再试"))
        assertFalse(LoginPolicy.passwordRejected("认证未通过，未取得票据。请检查账号状态或稍后重试"))
    }

    @Test
    fun detectsInteractionRequiredMessages() {
        assertTrue(LoginPolicy.requiresInteraction("请输入验证码"))
        assertTrue(LoginPolicy.requiresInteraction("验证码错误，请重新输入"))
        assertTrue(LoginPolicy.requiresInteraction("短信验证码已发送，请填写后再次点击登录"))
        assertTrue(LoginPolicy.requiresInteraction("登录尝试过多，账号已被临时锁定，请稍后再试"))
        assertTrue(LoginPolicy.requiresInteraction("地址已锁定"))
        assertFalse(LoginPolicy.requiresInteraction("用户名或密码错误。"))
        assertFalse(LoginPolicy.requiresInteraction(""))
    }

    @Test
    fun onlyForgetsPasswordWithoutInteraction() {
        assertTrue(LoginPolicy.shouldForgetPassword("学号或密码错误（HTTP 401）", false))
        // 验证码可能填错，密码本身未必错误：保留密码，等用户完成验证。
        assertFalse(
            LoginPolicy.shouldForgetPassword(
                "账号、密码或验证码错误，请重试",
                LoginPolicy.requiresInteraction("账号、密码或验证码错误，请重试"),
            ),
        )
        assertFalse(LoginPolicy.shouldForgetPassword("账号已被临时锁定", false))
        assertFalse(LoginPolicy.shouldForgetPassword("请输入验证码", false))
    }
}
