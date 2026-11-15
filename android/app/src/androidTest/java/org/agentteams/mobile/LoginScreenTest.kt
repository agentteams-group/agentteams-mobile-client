package org.agentteams.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingFieldsCannotSubmit() {
        compose.setContent { MaterialTheme { LoginScreen(false) { _, _, _, _, _ -> error("Must not submit empty login") } } }
        compose.onNodeWithText("登录").performScrollTo().assertIsNotEnabled()
    }

    @Test fun completeFormPassesPasswordUnmodified() {
        var result: List<String>? = null
        compose.setContent { MaterialTheme { LoginScreen(false) { matrix, controller, user, password, _ -> result = listOf(matrix, controller, user, password) } } }
        compose.onNodeWithText("Matrix 地址").performTextInput("https://matrix.example.com")
        compose.onNodeWithText("Controller 地址").performTextInput("https://at.example.com")
        compose.onNodeWithText("用户名或 Matrix ID").performTextInput("alice")
        compose.onNodeWithText("密码").performScrollTo().performTextInput(" secret ")
        assertNull(result)
        compose.onNodeWithText("登录").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("https://matrix.example.com", "https://at.example.com", "alice", " secret "), result) }
    }
}
