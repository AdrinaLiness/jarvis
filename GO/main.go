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

	if err := loadShortcuts(); err != nil {
		log.Printf("[!] shortcuts.json: %v", err)
		shortcuts = map[string]string{}
	}

	go startServer()
	time.Sleep(700 * time.Millisecond)

	log.Println("[*] Запускаю JARVIS...")
	runGUI() // GUI-окно (webview) или серверный режим — см. gui_webview.go / gui_server.go
}
