//go:build headless

package main

// Серверный режим (без GUI): JARVIS работает как локальный сервер,
// интерфейс открывается в браузере. Нужен для машин без WebView2/WebKit
// и для сборки без CGO:  CGO_ENABLED=0 go build -tags headless

import (
	"log"
	"time"
)

func runGUI() {
	url := "http://localhost:8000/jarvis.html"
	log.Println("[*] Серверный режим (сборка headless): интерфейс — в браузере")
	if err := openTarget(url); err != nil {
		log.Printf("[!] Браузер не открылся (%v) — открой %s вручную", err, url)
	}
	log.Println("[*] JARVIS работает. Останов: Ctrl+C или закрыть окно консоли")
	for {
		time.Sleep(time.Hour)
	}
}
