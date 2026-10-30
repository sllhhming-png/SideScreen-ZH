import Foundation
import AppKit

print("🚀 Side Screen starting...")

// Entry point
let app = NSApplication.shared

// Setup main menu for keyboard shortcuts (Command+Q, etc.)
let mainMenu = NSMenu()

// App menu
let appMenu = NSMenu()
let appMenuItem = NSMenuItem()
appMenuItem.submenu = appMenu
appMenu.addItem(NSMenuItem(title: "关于 Side Screen", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: ""))
appMenu.addItem(NSMenuItem.separator())
appMenu.addItem(NSMenuItem(title: "退出 Side Screen", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
mainMenu.addItem(appMenuItem)

// Edit menu (for standard text editing shortcuts)
let editMenu = NSMenu(title: "编辑")
let editMenuItem = NSMenuItem()
editMenuItem.submenu = editMenu
editMenu.addItem(NSMenuItem(title: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x"))
editMenu.addItem(NSMenuItem(title: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c"))
editMenu.addItem(NSMenuItem(title: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v"))
editMenu.addItem(NSMenuItem(title: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"))
mainMenu.addItem(editMenuItem)

app.mainMenu = mainMenu

let delegate = AppDelegate()
app.delegate = delegate
app.run()
