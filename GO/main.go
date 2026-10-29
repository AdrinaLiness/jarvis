package main

import (
        "embed"
        "encoding/json"
        "fmt"
        "io"
        "io/fs"
        "log"
        "net/http"
        "os"
        "os/exec"
        "path/filepath"
        "runtime"
        "sort"
        "strings"
        "sync"
        "time"
)

//go:embed web/*
var webFiles embed.FS

var (
        shortcuts  map[string]string
        shortcutsM sync.RWMutex
        baseDir    string
)

// ==================== Версия и проверка обновлений ====================
// При каждом запуске JARVIS в фоне сверяет свою версию с VERSION.json
// из репозитория; если вышла новая — пишет в лог и показывает баннер
// в интерфейсе (GET /api/version читает веб-страница jarvis.html).
// Лаунчер (JARVIS-Launcher.bat) при этом скачает и поставит свежие файлы.

const appVersion = "1.3"

const versionURL = "https://raw.githubusercontent.com/AdrinaLiness/jarvis/main/VERSION.json"

type versionInfo struct {
        Current         string `json:"current"`
        Latest          string `json:"latest"`
        UpdateAvailable bool   `json:"update_available"`
        Checked         bool   `json:"checked"`
        Download        string `json:"download"`
}

var updInfo = versionInfo{
        Current:  appVersion,
        Latest:   appVersion,
        Download: "https://github.com/AdrinaLiness/jarvis/releases/latest",
}

// verTuple: "1.10" -> [1 10] — сравнение версий по-числовому, а не по-строчному
func verTuple(s string) []int {
        parts := strings.Split(s, ".")
        res := make([]int, 0, 4)
        for _, p := range parts {
                digits := ""
                for _, r := range p {
                        if r >= '0' && r <= '9' {
                                digits += string(r)
                        } else {
                                break
                        }
                }
                n := 0
                fmt.Sscanf(digits, "%d", &n)
                res = append(res, n)
        }
        if len(res) == 0 {
                res = append(res, 0)
        }
        return res
}

func verLess(a, b []int) bool {
        n := len(a)
        if len(b) > n {
                n = len(b)
        }
        for i := 0; i < n; i++ {
                x, y := 0, 0
                if i < len(a) {
                        x = a[i]
                }
                if i < len(b) {
                        y = b[i]
                }
                if x < y {
                        return true
                }
                if x > y {
                        return false
                }
        }
        return false
}

// checkForUpdates сверяет версию с VERSION.json в репозитории.
// Любая ошибка (нет интернета, таймаут, GitHub недоступен) — не проблема:
// JARVIS просто работает дальше, проверка повторится при следующем запуске.
func checkForUpdates() {
        client := &http.Client{Timeout: 6 * time.Second}
        req, err := http.NewRequest("GET", versionURL, nil)
        if err != nil {
                return
        }
        req.Header.Set("User-Agent", "JARVIS-go")
        resp, err := client.Do(req)
        if err != nil {
                log.Printf("[i] Проверка обновлений пропущена (%v)", err)
                return
        }
        defer resp.Body.Close()
        var data struct {
                Components map[string]string `json:"components"`
                Download   string            `json:"download"`
        }
        if err := json.NewDecoder(resp.Body).Decode(&data); err != nil {
                log.Printf("[i] Проверка обновлений пропущена (%v)", err)
                return
        }
        updInfo.Checked = true
        // один и тот же бинарник расходится и как EXE-сборка, и как исходники GO,
        // поэтому смотрим обе строки VERSION.json и берём самую свежую
        latest := data.Components["go"]
        if e := data.Components["exe"]; e != "" && verLess(verTuple(latest), verTuple(e)) {
                latest = e
        }
        if data.Download != "" {
                updInfo.Download = data.Download
        }
        if latest != "" && verLess(verTuple(appVersion), verTuple(latest)) {
                updInfo.Latest = latest
                updInfo.UpdateAvailable = true
                log.Printf("[!] Доступна новая версия JARVIS: %s (у тебя %s). Обновление: запусти JARVIS-Launcher.bat заново — он скачает свежую версию.", latest, appVersion)
        } else if latest != "" {
                updInfo.Latest = latest
                log.Printf("[i] Версия JARVIS актуальна: %s", appVersion)
        }
}

func loadShortcuts() error {
        data, err := os.ReadFile(filepath.Join(baseDir, "shortcuts.json"))
        if err != nil {
                return err
        }
        var m map[string]string
        if err := json.Unmarshal(data, &m); err != nil {
                return err
        }
        shortcutsM.Lock()
        shortcuts = m
        shortcutsM.Unlock()
        log.Printf("[+] Ярлыков: %d", len(m))
        keys := make([]string, 0, len(m))
        for k := range m {
                keys = append(keys, k)
        }
        sort.Strings(keys)
        for _, k := range keys {
                log.Printf("    - %s → %s", k, m[k])
        }
        return nil
}

func openTarget(target string) error {
        if strings.TrimSpace(target) == "" {
                return fmt.Errorf("пустая цель")
        }
        switch runtime.GOOS {
        case "windows":
                return exec.Command("rundll32", "url.dll,FileProtocolHandler", target).Start()
        case "darwin":
                return exec.Command("open", target).Start()
        default:
                return exec.Command("xdg-open", target).Start()
        }
}

func openInBrowser(browserPath, url string) error {
        if browserPath == "" {
                return openTarget(url)
        }
        return exec.Command(browserPath, url).Start()
}

type BrowserInfo struct {
        Name string `json:"name"`
        Path string `json:"path"`
}

func findBrowsers() []BrowserInfo {
        home, _ := os.UserHomeDir()
        var candidates []BrowserInfo
        switch runtime.GOOS {
        case "windows":
                candidates = []BrowserInfo{
                        {"Chrome", `C:\Program Files\Google\Chrome\Application\chrome.exe`},
                        {"Chrome", `C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`},
                        {"Edge", `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`},
                        {"Edge", `C:\Program Files\Microsoft\Edge\Application\msedge.exe`},
                        {"Firefox", `C:\Program Files\Mozilla Firefox\firefox.exe`},
                        {"Opera", `C:\Program Files\Opera\opera.exe`},
                        {"Yandex", os.Getenv("LOCALAPPDATA") + `\Yandex\YandexBrowser\Application\browser.exe`},
                        {"Brave", `C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe`},
                        {"Vivaldi", os.Getenv("LOCALAPPDATA") + `\Vivaldi\Application\vivaldi.exe`},
                }
        default:
                candidates = []BrowserInfo{
                        {"Chrome", "/usr/bin/google-chrome"},
                        {"Chromium", "/usr/bin/chromium"},
                        {"Firefox", "/usr/bin/firefox"},
                        {"Opera", "/usr/bin/opera"},
                        {"Brave", "/usr/bin/brave-browser"},
                        {"Яндекс", "/usr/bin/yandex-browser"},
                        {"Яндекс", "/opt/yandex-browser/yandex-browser"},
                }
                if home != "" {
                        candidates = append(candidates,
                                BrowserInfo{"Firefox", filepath.Join(home, "snap", "bin", "firefox")},
                                BrowserInfo{"Яндекс", filepath.Join(home, ".local", "share", "yandex-browser", "yandex-browser")})
                }
        }
        var found []BrowserInfo
        seen := map[string]bool{}
        for _, b := range candidates {
                if b.Path == "" {
                        continue
                }
                if _, err := os.Stat(b.Path); err == nil && !seen[b.Name] {
                        found = append(found, b)
                        seen[b.Name] = true
                }
        }
        return found
}

func startServer() {
        sub, err := fs.Sub(webFiles, "web")
        if err != nil {
                log.Printf("[!] web/: %v", err)
                return
        }
        mux := http.NewServeMux()
        mux.Handle("/", http.FileServer(http.FS(sub)))

        cors := func(w http.ResponseWriter) {
                w.Header().Set("Access-Control-Allow-Origin", "*")
        }

        mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
                cors(w)
                w.Header().Set("Content-Type", "application/json; charset=utf-8")
                shortcutsM.RLock()
                n := len(shortcuts)
                shortcutsM.RUnlock()
                fmt.Fprintf(w, `{"status":"online","shortcuts":%d,"os":%q,"commands":%d}`, n, runtime.GOOS, len(commands))
        })

        mux.HandleFunc("/api/commands", func(w http.ResponseWriter, r *http.Request) {
                cors(w)
                w.Header().Set("Content-Type", "application/json; charset=utf-8")
                w.Write([]byte(commandsListJSON()))
        })

        mux.HandleFunc("/api/exec", func(w http.ResponseWriter, r *http.Request) {
                cors(w)
                w.Header().Set("Content-Type", "application/json; charset=utf-8")
                var buf strings.Builder
                if _, err := fmt.Fprint(&buf, r.URL.Query().Get("p")); err != nil {
                        // ignore
                }
                payload := r.URL.Query().Get("p")
                if payload == "" && r.Method == http.MethodPost {
                        tmp := make([]byte, 64*1024)
                        n, _ := r.Body.Read(tmp)
                        payload = string(tmp[:n])
                }
                log.Printf("[*] exec: %s", payload)
                w.Write([]byte(execCommandJSON(payload)))
        })

        // Браузерный режим UI: метки [[OPEN:…]] / [[RUN:…]] / поиск из ответов ИИ.
        mux.HandleFunc("/api/open", func(w http.ResponseWriter, r *http.Request) {
                cors(w)
                w.Header().Set("Content-Type", "application/json; charset=utf-8")
                var payload struct {
                        Name   string `json:"name"`
                        Target string `json:"target"`
                }
                body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024))
                if err == nil && len(body) > 0 {
                        _ = json.Unmarshal(body, &payload)
                }
                if payload.Name == "" && payload.Target == "" {
                        payload.Name = r.URL.Query().Get("name")
                        payload.Target = r.URL.Query().Get("target")
                }
                log.Printf("[*] open: %s", payload.Name+payload.Target)
                switch {
                case payload.Name != "":
                        shortcutsM.RLock()
                        target, ok := shortcuts[strings.ToLower(payload.Name)]
                        shortcutsM.RUnlock()
                        if !ok {
                                fmt.Fprintf(w, `{"ok":false,"error":"ярлык не найден: %s"}`, payload.Name)
                                return
                        }
                        if err := openTarget(target); err != nil {
                                fmt.Fprintf(w, `{"ok":false,"error":%q}`, err.Error())
                                return
                        }
                        fmt.Fprintf(w, `{"ok":true,"msg":"Запущено: %s"}`, payload.Name)
                case payload.Target != "":
                        if err := openTarget(payload.Target); err != nil {
                                fmt.Fprintf(w, `{"ok":false,"error":%q}`, err.Error())
                                return
                        }
                        fmt.Fprint(w, `{"ok":true,"msg":"Открыто"}`)
                default:
                        fmt.Fprint(w, `{"ok":false,"error":"пустой запрос"}`)
                }
        })

        mux.HandleFunc("/api/browsers", func(w http.ResponseWriter, r *http.Request) {
                cors(w)
                w.Header().Set("Content-Type", "application/json; charset=utf-8")
                json.NewEncoder(w).Encode(map[string]interface{}{"browsers": findBrowsers()})
        })

        mux.HandleFunc("/api/version", func(w http.ResponseWriter, r *http.Request) {
                cors(w)
                w.Header().Set("Content-Type", "application/json; charset=utf-8")
                json.NewEncoder(w).Encode(updInfo)
        })

        log.Println("[+] Сервер: http://localhost:8000")
        if err := http.ListenAndServe(":8000", mux); err != nil {
                log.Printf("[!] Сервер: %v", err)
        }
}

func main() {
        exe, _ := os.Executable()
        baseDir = filepath.Dir(exe)
        if strings.Contains(exe, "go-build") || strings.Contains(exe, "Temp") {
                baseDir, _ = os.Getwd()
        }
        log.Printf("[*] Папка: %s", baseDir)
        log.Printf("[*] ОС: %s", runtime.GOOS)
        log.Printf("[*] Команд загружено: %d", len(commands))

        // Проверка обновлений — при каждом запуске, в фоне (не тормозит старт)
        go func() {
                time.Sleep(1500 * time.Millisecond)
                checkForUpdates()
        }()

        if err := loadShortcuts(); err != nil {
                log.Printf("[!] shortcuts.json: %v", err)
                shortcuts = map[string]string{}
        }

        go startServer()
        time.Sleep(700 * time.Millisecond)

        log.Println("[*] Запускаю JARVIS...")
        runGUI() // GUI-окно (webview) или серверный режим — см. gui_webview.go / gui_server.go
}
