//go:build !headless

package main

// GUI-режим: окно webview (на Windows — WebView2, на Linux — WebKitGTK).
// Обычная сборка:        go build -o jarvis.exe
// Серверный режим:       go build -tags headless  (без GUI, интерфейс в браузере)

import (
        "encoding/json"
        "sort"
        "strings"

        webview "github.com/webview/webview_go"
)

func runGUI() {
        w := webview.New(false)
        defer w.Destroy()
        w.SetTitle("J.A.R.V.I.S.")
        w.SetSize(1400, 900, webview.HintNone)

        // ГЛАВНАЯ ТОЧКА ВХОДА: одна функция на все команды
        w.Bind("jarvisExec", func(payload string) string {
                return execCommandJSON(payload)
        })

        // Совместимость со старым интерфейсом
        w.Bind("jarvisRun", func(name string) string {
                shortcutsM.RLock()
                target, ok := shortcuts[strings.ToLower(name)]
                shortcutsM.RUnlock()
                if !ok {
                        return "❌ Не найдено: " + name
                }
                if err := openTarget(target); err != nil {
                        return "❌ " + err.Error()
                }
                return "✅ Запущено: " + name
        })
        w.Bind("jarvisOpen", func(target string) string {
                if err := openTarget(target); err != nil {
                        return "❌ " + err.Error()
                }
                return "✅ Открыто"
        })
        w.Bind("jarvisOpenBrowser", func(url string) string {
                if err := openTarget(url); err != nil {
                        return "❌ " + err.Error()
                }
                return "✅ Открыто в браузере"
        })
        w.Bind("jarvisOpenIn", func(browser, url string) string {
                if err := openInBrowser(browser, url); err != nil {
                        return "❌ " + err.Error()
                }
                return "✅ Открыто"
        })
        w.Bind("jarvisBrowsers", func() string {
                data, _ := json.Marshal(findBrowsers())
                return string(data)
        })
        w.Bind("jarvisReload", func() string {
                if err := loadShortcuts(); err != nil {
                        return "❌ " + err.Error()
                }
                return "✅ Обновлено"
        })
        w.Bind("jarvisList", func() string {
                shortcutsM.RLock()
                defer shortcutsM.RUnlock()
                keys := make([]string, 0, len(shortcuts))
                for k := range shortcuts {
                        keys = append(keys, k)
                }
                sort.Strings(keys)
                return strings.Join(keys, ", ")
        })

        w.Navigate("http://localhost:8000/jarvis.html")
        w.Run()
}
