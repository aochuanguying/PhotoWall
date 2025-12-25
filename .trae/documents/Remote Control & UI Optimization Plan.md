# Implementation Plan

I will modify the following files to address your requests:

1. **`app/src/main/java/com/hxfssc/activity/MainActivity.java`**:

   * **Add** **`exitApp()`** **to** **`JSBridge`**: Allow the web page to close the application.

   * **Handle Back Key**: Intercept the Back button. If in `photo_wall.html`, delegate to `window.handleBackPress()`. If in other pages (like Settings), go back in history.

2. **`app/src/main/assets/photo_wall.html`**:

   * **Menu Update**: Add a "退出菜单" (Exit Menu) item to the refresh menu.

   * **Key Logic**:

     * Remove the Down key (40) binding (no longer opens settings).

     * Implement `window.handleBackPress()`:

       * If menu is open -> Close menu.

       * If menu is closed -> Call `Native.exitApp()`.

   * **Progress Display**: Update `runIncrementalRefresh()` to show the message container with "正在刷新..." text, ensuring visual feedback for both refresh types.

   * **JS Logic**: Implement `closeMenu()` to hide the menu and return focus/state to normal.

3. **`app/src/main/assets/manager/index.html`**:

   * **Focus Optimization**: Add `tabindex="-1"` to the footer email link so it is skipped during remote control navigation.

## Execution Steps

1. Modify `MainActivity.java` to add `exitApp` and Back key handling.
2. Modify `photo_wall.html` to update menu HTML, key bindings, and refresh feedback.
3. Modify `manager/index.html` to fix the footer focus issue.

