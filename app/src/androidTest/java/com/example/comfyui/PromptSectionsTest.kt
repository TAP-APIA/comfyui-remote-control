package com.example.comfyui

import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptSectionsTest {
    @Test fun savedSectionsRestoreInOrderAndReachPromptInput() {
        val name = "分段测试-${System.currentTimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                ViewModelProvider(it)[MainViewModel::class.java].setParamConfig(ParamConfig("", "", "5", "12", "640", "352", listOf("")))
            }
            onView(withContentDescription("提示词第1段")).perform(scrollTo(), replaceText("风景描述"), closeSoftKeyboard())
            onView(withId(R.id.add_prompt_section_button)).perform(scrollTo(), click())
            onView(withContentDescription("提示词第2段")).perform(scrollTo(), replaceText("镜头描述"), closeSoftKeyboard())
            onView(allOf(withText("上移"), isEnabled())).perform(scrollTo(), click())
            onView(withContentDescription("提示词第1段")).check(matches(withText("镜头描述")))
            onView(withId(R.id.duration_edit_text)).perform(scrollTo(), replaceText("5"), closeSoftKeyboard())
            onView(withId(R.id.steps_edit_text)).perform(scrollTo(), replaceText("12"), closeSoftKeyboard())
            onView(withId(R.id.save_params_button)).perform(scrollTo(), click())
            onView(allOf(isAssignableFrom(EditText::class.java), withHint("参数配置名称"))).perform(replaceText(name), closeSoftKeyboard())
            onView(withText("保存为新")).perform(click())
            onView(withContentDescription("提示词第1段")).perform(scrollTo(), replaceText("临时修改"), closeSoftKeyboard())
            onView(withId(R.id.load_params_button)).perform(scrollTo(), click())
            onView(withText(name)).perform(click())
            onView(withContentDescription("提示词第1段")).check(matches(withText("镜头描述")))
            onView(withContentDescription("提示词第2段")).check(matches(withText("风景描述")))
            scenario.recreate()
            onView(withContentDescription("提示词第1段")).check(matches(withText("镜头描述")))
            scenario.onActivity { activity ->
                val config = ViewModelProvider(activity)[MainViewModel::class.java].currentParams.value!!
                assertEquals(listOf("镜头描述", "风景描述"), config.sections())
                assertEquals("5", config.duration)
                assertEquals("12", config.steps)
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                val fragment = host.childFragmentManager.primaryNavigationFragment as FirstFragment
                val workflow = JsonParser.parseString("""{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"old"}}}""").asJsonObject
                FirstFragment::class.java.getDeclaredMethod("injectParameters", com.google.gson.JsonObject::class.java).apply {
                    isAccessible = true
                }.invoke(fragment, workflow)
                assertEquals("镜头描述\n\n风景描述", workflow.getAsJsonObject("1").getAsJsonObject("inputs").get("text").asString)
            }
        }
    }
}
