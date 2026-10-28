package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ---------- ТИПЫ ----------

type Result struct {
	OK   bool        `json:"ok"`
	Msg  string      `json:"msg"`
	Data interface{} `json:"data,omitempty"`
}

type Args map[string]string

func (a Args) get(k string) string { return strings.TrimSpace(a[k]) }

func (a Args) str(k, def string) string {
	if v := a.get(k); v != "" {
		return v
	}
	return def
}

func (a Args) num(k string, def int) int {
	if v, err := strconv.Atoi(a.get(k)); err == nil {
		return v
	}
	return def
}

type CmdFunc func(Args) Result

type CommandInfo struct {
	Name string `json:"name"`
	Desc string `json:"desc"`
	Args string `json:"args"`
	Cat  string `json:"cat"`
}

var (
	commands    = map[string]CmdFunc{}
	commandMeta []CommandInfo
)

func add(name, args, desc, cat string, fn CmdFunc) {
	commands[name] = fn
	commandMeta = append(commandMeta, CommandInfo{Name: name, Args: args, Desc: desc, Cat: cat})
}

func ok(msg string, data ...interface{}) Result {
	r := Result{OK: true, Msg: msg}
	if len(data) > 0 {
		r.Data = data[0]
	}
	return r
}

func fail(msg string) Result { return Result{OK: false, Msg: msg} }

// ---------- СИСТЕМНЫЕ ХЕЛПЕРЫ ----------

func isWin() bool { return runtime.GOOS == "windows" }

// shellOut выполняет команду и возвращает вывод (Windows или Linux)
func shellOut(script string) (string, error) {
	if isWin() {
		c := exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
		var buf bytes.Buffer
		c.Stdout = &buf
		c.Stderr = &buf
		err := c.Run()
		return strings.TrimSpace(buf.String()), err
	}
	c := exec.Command("bash", "-lc", script)
	var buf bytes.Buffer
	c.Stdout = &buf
	c.Stderr = &buf
	err := c.Run()
	return strings.TrimSpace(buf.String()), err
}

func run(name string, args ...string) error {
	return exec.Command(name, args...).Start()
}

func expandPath(p string) string {
	p = strings.TrimSpace(p)
	if p == "" {
		return p
	}
	if strings.HasPrefix(p, "~") {
		if home, err := os.UserHomeDir(); err == nil {
			p = filepath.Join(home, strings.TrimPrefix(strings.TrimPrefix(p, "~"), string(filepath.Separator)))
		}
	}
	return os.ExpandEnv(p)
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func dirExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func humanBytes(b uint64) string {
	var u = []string{"Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ"}
	f := float64(b)
	i := 0
	for f >= 1024 && i < len(u)-1 {
		f /= 1024
		i++
	}
	return fmt.Sprintf("%.1f %s", f, u[i])
}

func kbToStr(kb float64) string { return humanBytes(uint64(kb * 1024)) }

// sendKey имитирует нажатие клавиши (медиа-клавиши и Win+D и т.п.)
func sendKey(vk byte, count int) error {
	script := `
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class KB {
  [DllImport("user32.dll")] public static extern void keybd_event(byte b, byte s, uint f, UIntPtr e);
}'
`
	if isWin() {
		script += fmt.Sprintf("$KB=[KB]; for($i=0;$i -lt %d;$i++){[void]$KB::keybd_event(0x%02X,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [void]$KB::keybd_event(0x%02X,0,2,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30}", count, vk, vk)
	} else {
		script = fmt.Sprintf("for i in $(seq 1 %d); do xdotool key %d; sleep 0.05; done", count, vk)
	}
	_, err := shellOut(script)
	return err
}

func mediaKey(vk byte) error { return sendKey(vk, 1) }

func mediaKeyRepeat(vk byte, n int) error { return sendKey(vk, n) }

// ---------- РЕГИСТРАЦИЯ КОМАНД ----------

func init() {
	// ===== СИСТЕМА =====
	add("help", "", "список всех команд", "система", func(a Args) Result {
		var b strings.Builder
		byCat := map[string][]CommandInfo{}
		for _, m := range commandMeta {
			byCat[m.Cat] = append(byCat[m.Cat], m)
		}
		for cat, list := range byCat {
			b.WriteString("\n[" + strings.ToUpper(cat) + "]\n")
			for _, m := range list {
				b.WriteString("  " + m.Name)
				if m.Args != "" {
					b.WriteString(" " + m.Args)
				}
				b.WriteString(" — " + m.Desc + "\n")
			}
		}
		return ok("Команды JARVIS:", b.String())
	})

	add("sysinfo", "", "полная информация о системе", "система", func(a Args) Result {
		if isWin() {
			out, err := shellOut(`
$os=Get-CimInstance Win32_OperatingSystem
$cs=Get-CimInstance Win32_ComputerSystem
$cpu=Get-CimInstance Win32_Processor | Select-Object -First 1
$gpu=(Get-CimInstance Win32_VideoController | Select-Object -First 1).Name
$up=(Get-Date)-$os.LastBootUpTime
"Компьютер:  " + $env:COMPUTERNAME
"Пользователь: " + $env:USERNAME
"ОС:         " + $os.Caption + " (сборка " + $os.BuildNumber + ")"
"Архитектура: " + $os.OSArchitecture
"Процессор:  " + $cpu.Name
"Ядер:       " + $cpu.NumberOfCores + " (потоков " + $cpu.NumberOfLogicalProcessors + ")"
"ОЗУ:        " + [math]::Round($cs.TotalPhysicalMemory/1GB,1) + " ГБ"
"GPU:        " + $gpu
"Диск C:     " + [math]::Round((Get-PSDrive C).Used/1GB,1) + " / " + [math]::Round((Get-PSDrive C).Free/1GB,1) + " ГБ (свободно)"
"Загрузка:   " + [math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average,0) + "%"
"Uptime:     " + [int]$up.TotalHours + " ч " + $up.Minutes + " мин"
"IP:         " + (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -notlike '127.*'} | Select-Object -First 1 -ExpandProperty IPAddress)
`)
			if err != nil && out == "" {
				return fail("ошибка: " + err.Error())
			}
			return ok("Система:", out)
		}
		out, err := shellOut(`echo "Хост:    $(hostname)"; echo "Ядро:    $(uname -r)"; echo "ОС:      $(. /etc/os-release 2>/dev/null && echo $PRETTY_NAME || echo Linux)"; echo "CPU:     $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)"; echo "Ядер:    $(nproc)"; echo "ОЗУ:     $(free -h | awk '/^Mem:/{print $2" всего, "$7" свободно"}')"; echo "Uptime:  $(uptime -p)"; echo "Диск /:  $(df -h / | awk 'NR==2{print $3" из "$2" ("$5")"}')"; echo "IP:      $(hostname -I | awk '{print $1}')"`)
		if err != nil && out == "" {
			return fail("ошибка: " + err.Error())
		}
		return ok("Система:", out)
	})

	add("uptime", "", "сколько работает компьютер", "система", func(a Args) Result {
		if isWin() {
			out, _ := shellOut(`$os=Get-CimInstance Win32_OperatingSystem; $u=(Get-Date)-$os.LastBootUpTime; "Работает: " + [int]$u.TotalHours + " ч " + $u.Minutes + " мин " + $u.Seconds + " сек"` + "\n" + `"Загружено: " + $os.LastBootUpTime`)
			return ok("Uptime", out)
		}
		out, _ := shellOut("uptime -p")
		return ok("Uptime", out)
	})

	add("disks", "", "свободное место на дисках", "система", func(a Args) Result {
		if isWin() {
			out, _ := shellOut(`Get-PSDrive -PSProvider FileSystem | Where-Object {$_.Used -ne $null} | ForEach-Object {
  $t=$_.Used+$_.Free
  $bar=[math]::Round($_.Free/$t*100)
  "$($_.Name):  " + [math]::Round($_.Free/1GB,1) + " ГБ свободно из " + [math]::Round($t/1GB,1) + " ГБ  (" + $bar + "% свободно)"
}`)
			return ok("Диски:", out)
		}
		out, _ := shellOut("df -h -x tmpfs -x devtmpfs | awk 'NR==1 || $1 ~ /^\\//'")
		return ok("Диски:", out)
	})

	add("net", "", "сетевая информация", "система", func(a Args) Result {
		if isWin() {
			out, _ := shellOut(`
"Активный адаптер:"
Get-NetAdapter | Where-Object {$_.Status -eq 'Up'} | ForEach-Object { "  " + $_.Name + " (" + $_.InterfaceDescription + ")" }
"IPv4:"
Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -notlike '127.*'} | ForEach-Object { "  " + $_.IPAddress + "  шлюз " + $_.PrefixOrigin }
"Публичный IP:"
try { (Invoke-WebRequest -Uri 'https://api.ipify.org' -UseBasicParsing -TimeoutSec 6).Content } catch { "  нет связи с сервисом" }
`)
			return ok("Сеть:", out)
		}
		out, _ := shellOut("ip -brief addr show | grep -v ' lo '; echo; ip route | head -5; echo; curl -s --max-time 6 https://api.ipify.org || echo 'нет связи'")
		return ok("Сеть:", out)
	})

	add("ping", "<хост>", "ping узла", "сеть", func(a Args) Result {
		h := a.str("arg", "8.8.8.8")
		if isWin() {
			out, _ := shellOut("ping -n 4 " + h)
			return ok("Ping "+h+":", out)
		}
		out, _ := shellOut("ping -c 4 " + h)
		return ok("Ping "+h+":", out)
	})

	add("traceroute", "<хост>", "трассировка маршрута", "сеть", func(a Args) Result {
		h := a.str("arg", "8.8.8.8")
		if isWin() {
			out, _ := shellOut("tracert -d -h 12 -w 800 " + h)
			return ok("Трассировка "+h+":", out)
		}
		out, _ := shellOut("traceroute -n -m 12 -w 1 " + h)
		return ok("Трассировка "+h+":", out)
	})

	add("wifi", "[ssid]", "список Wi-Fi / подключиться", "сеть", func(a Args) Result {
		if !isWin() {
			out, _ := shellOut("nmcli dev wifi list 2>/dev/null || iwconfig")
			return ok("Wi-Fi:", out)
		}
		ssid := a.get("arg")
		if ssid == "" {
			out, _ := shellOut(`netsh wlan show interfaces | Select-String -Pattern 'Name|State|Состояние|Сигнал|Signal|Канал|Channel' | ForEach-Object { "  " + $_.Line }` + "`n--- Доступные сети ---`n" + `netsh wlan show networks mode=bssid | Select-String -Pattern '^SSID' | ForEach-Object { "  " + ($_ -replace '.*SSID\s*:\s*','') -replace '^SSID\s*:\s*','' }`)
			return ok("Wi-Fi:", out)
		}
		out, _ := shellOut("netsh wlan connect name=\"" + ssid + "\"")
		return ok("Подключение к "+ssid, out)
	})

	add("ipconfig", "", "сброс DNS и сети", "сеть", func(a Args) Result {
		if isWin() {
			shellOut("ipconfig /flushdns")
			shellOut("ipconfig /renew")
			return ok("DNS очищен, IP обновлён")
		}
		shellOut("sudo -n resolvectl flush-caches 2>/dev/null || true")
		return ok("DNS-кэш очищен")
	})

	// ===== ПРОЦЕССЫ =====
	add("ps", "[поиск]", "список процессов (топ по CPU)", "процессы", func(a Args) Result {
		filter := strings.ToLower(a.get("arg"))
		if isWin() {
			script := `
$p1=@{}; Get-Process | ForEach-Object { $p1[$_.Id]=$_.CPU }
Start-Sleep -Milliseconds 600
$res=@()
Get-Process | ForEach-Object {
  if($_.CPU -ne $null){
    $prev=$p1[$_.Id]; if($prev -ne $null){
      $d=$_.CPU-$prev
      if($d -lt 0){$d=0}
      $res += [pscustomobject]@{ Name=$_.ProcessName; PID=$_.Id; CPU=[math]::Round($d*100/0.6,1); MEM=[math]::Round($_.WorkingSet64/1MB,1) }
    }
  }
}
$res | Sort-Object CPU -Descending | Select-Object -First 18 | ConvertTo-Csv -NoTypeInformation
`
			out, _ := shellOut(script)
			lines := strings.Split(out, "\n")
			if len(lines) < 2 {
				return ok("Процессы:", out)
			}
			var b strings.Builder
			b.WriteString(fmt.Sprintf("%-22s %-8s %-8s %s\n", "ПРОЦЕСС", "PID", "CPU%", "ОЗУ"))
			for i, l := range lines {
				if i == 0 {
					continue
				}
				f := strings.Split(strings.Trim(l, `"`), ",")
				if len(f) < 4 {
					continue
				}
				if filter != "" && !strings.Contains(strings.ToLower(f[0]), filter) {
					continue
				}
				b.WriteString(fmt.Sprintf("%-22s %-8s %-8s %s МБ\n", f[0], f[1], f[2], f[3]))
			}
			return ok("Топ процессов по CPU:", b.String())
		}
		out, _ := shellOut("ps -eo comm,pid,pcpu,pmem --sort=-pcpu | head -19")
		return ok("Топ процессов:", out)
	})

	add("findproc", "<имя>", "найти процесс по имени", "процессы", func(a Args) Result {
		n := a.str("arg", "")
		if n == "" {
			return fail("укажите имя процесса: findproc chrome")
		}
		if isWin() {
			out, _ := shellOut("Get-Process -Name \"" + n + "\" -ErrorAction SilentlyContinue | Select-Object ProcessName,Id,@{N='ОЗУ_МБ';E={[math]::Round($_.WorkingSet64/1MB,1)}} | Format-Table -AutoSize | Out-String -Width 200")
			if strings.TrimSpace(out) == "" {
				return fail("процесс «" + n + "» не найден")
			}
			return ok("Найдено:", out)
		}
		out, _ := shellOut("ps -eo pid,comm,pcpu,pmem | grep -i " + n + " | grep -v grep")
		if strings.TrimSpace(out) == "" {
			return fail("процесс «" + n + "» не найден")
		}
		return ok("Найдено:", out)
	})

	add("killproc", "<имя|pid>", "закрыть процесс", "процессы", func(a Args) Result {
		n := a.get("arg")
		if n == "" {
			return fail("укажите процесс: killproc chrome")
		}
		if pid, err := strconv.Atoi(n); err == nil {
			p, err := os.FindProcess(pid)
			if err != nil {
				return fail("не найден PID " + n)
			}
			if err := p.Kill(); err != nil {
				return fail("ошибка: " + err.Error())
			}
			return ok("Процесс " + n + " завершён")
		}
		if isWin() {
			out, err := shellOut("taskkill /F /IM \"" + n + "\" 2>&1")
			if err != nil || strings.Contains(out, "не найден") || strings.Contains(out, "not found") {
				return fail("не удалось закрыть «" + n + "»")
			}
			return ok("Процесс «" + n + "» закрыт")
		}
		shellOut("pkill -f " + n)
		return ok("Процесс «" + n + "» закрыт")
	})

	add("killtree", "<имя>", "закрыть процесс и все дочерние", "процессы", func(a Args) Result {
		n := a.get("arg")
		if n == "" {
			return fail("укажите процесс: killtree steam")
		}
		if isWin() {
			out, _ := shellOut("taskkill /F /T /IM \"" + n + "\" 2>&1")
			return ok("Закрыто «"+n+"» вместе с дочерними", out)
		}
		shellOut("pkill -9 -f " + n)
		return ok("Закрыто «" + n + "» вместе с дочерними")
	})

	add("startproc", "<имя>", "запустить программу по имени", "процессы", func(a Args) Result {
		n := a.str("arg", "")
		if n == "" {
			return fail("укажите программу: startproc notepad")
		}
		return launchByName(n)
	})

	// ===== ФАЙЛЫ =====
	add("openfolder", "<путь>", "открыть папку в проводнике", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", "~"))
		if p == "" {
			home, _ := os.UserHomeDir()
			p = home
		}
		if !dirExists(p) {
			return fail("папка не найдена: " + p)
		}
		if err := openTarget(p); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Открыта папка: " + p)
	})

	add("openfile", "<путь>", "открыть файл", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", ""))
		if p == "" {
			return fail("укажите путь к файлу")
		}
		if !fileExists(p) {
			return fail("файл не найден: " + p)
		}
		if err := openTarget(p); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Открыт файл: " + p)
	})

	add("reveal", "<путь>", "открыть проводник с выделенным файлом", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", "~"))
		if !fileExists(p) && !dirExists(p) {
			return fail("не найдено: " + p)
		}
		if isWin() {
			shellOut("explorer /select,\"" + p + "\"")
		} else {
			openTarget(filepath.Dir(p))
		}
		return ok("Показано в проводнике: " + p)
	})

	add("edit", "<путь>", "открыть файл в блокноте", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", ""))
		if p == "" {
			return fail("укажите путь к файлу")
		}
		if !fileExists(p) {
			f, err := os.Create(p)
			if err != nil {
				return fail("не удалось создать: " + err.Error())
			}
			f.Close()
		}
		if isWin() {
			if err := run("notepad.exe", p); err != nil {
				return fail("ошибка: " + err.Error())
			}
		} else {
			run("xdg-open", p)
		}
		return ok("Редактирование: " + p)
	})

	add("newfolder", "<путь>", "создать папку", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", ""))
		if p == "" {
			return fail("укажите путь: newfolder ~/Работа/Проект")
		}
		if dirExists(p) {
			return ok("Папка уже существует: " + p)
		}
		if err := os.MkdirAll(p, 0755); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Создана папка: " + p)
	})

	add("findfiles", "<шаблон> [папка]", "поиск файлов по имени", "файлы", func(a Args) Result {
		pat := a.str("arg", "")
		if pat == "" {
			return fail("укажите шаблон: findfiles *.txt")
		}
		root := expandPath(a.str("dir", "."))
		if !dirExists(root) {
			return fail("папка не найдена: " + root)
		}
		var found []string
		limit := a.num("limit", 40)
		filepath.Walk(root, func(p string, info os.FileInfo, err error) error {
			if err != nil {
				return nil
			}
			if len(found) >= limit {
				return filepath.SkipDir
			}
			matched, _ := filepath.Match(pat, info.Name())
			if matched || strings.Contains(strings.ToLower(info.Name()), strings.ToLower(strings.Trim(pat, "*"))) && !strings.Contains(pat, "*") {
				found = append(found, p)
			}
			return nil
		})
		if len(found) == 0 {
			return fail("ничего не найдено по «" + pat + "»")
		}
		return ok(fmt.Sprintf("Найдено %d (показаны все):", len(found)), strings.Join(found, "\n"))
	})

	add("filesize", "<путь>", "размер файла или папки", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", ""))
		if p == "" {
			return fail("укажите путь")
		}
		st, err := os.Stat(p)
		if err != nil {
			return fail("не найдено: " + p)
		}
		if !st.IsDir() {
			return ok("Файл: "+p, humanBytes(uint64(st.Size()))+"  (изменён "+st.ModTime().Format("02.01.2006 15:04")+")")
		}
		var total uint64
		var count int
		filepath.Walk(p, func(fp string, info os.FileInfo, e error) error {
			if e == nil && !info.IsDir() {
				total += uint64(info.Size())
				count++
			}
			return nil
		})
		return ok("Папка: "+p, fmt.Sprintf("%s в %d файлах", humanBytes(total), count))
	})

	add("recent", "", "недавние файлы", "файлы", func(a Args) Result {
		var recent string
		if isWin() {
			recent = os.Getenv("APPDATA") + `\Microsoft\Windows\Recent`
		} else {
			recent = os.Getenv("XDG_DATA_HOME")
			if recent == "" {
				recent, _ = os.UserHomeDir()
				recent = filepath.Join(recent, ".local", "share")
			}
			recent = filepath.Join(recent, "recently-used.xbel")
		}
		if !fileExists(recent) && !dirExists(recent) {
			return fail("список недавних недоступен")
		}
		if isWin() {
			entries, _ := os.ReadDir(recent)
			var lines []string
			for i, e := range entries {
				if i >= 15 {
					break
				}
				lines = append(lines, "  "+e.Name())
			}
			return ok("Недавние файлы:", strings.Join(lines, "\n"))
		}
		data, err := os.ReadFile(recent)
		if err != nil {
			return fail("ошибка чтения")
		}
		return ok("Недавние файлы:", string(data))
	})

	add("ls", "<путь>", "содержимое папки", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", "~"))
		entries, err := os.ReadDir(p)
		if err != nil {
			return fail("не удалось прочитать: " + p)
		}
		var dirs, files []string
		for _, e := range entries {
			if e.IsDir() {
				dirs = append(dirs, "  [ПАПКА] "+e.Name())
			} else {
				info, _ := e.Info()
				sz := ""
				if info != nil {
					sz = "  " + humanBytes(uint64(info.Size()))
				}
				files = append(files, "  "+e.Name()+sz)
			}
		}
		sort.Strings(dirs)
		sort.Strings(files)
		out := append(dirs, files...)
		if len(out) > 60 {
			out = append(out[:60], fmt.Sprintf("  ... и ещё %d", len(out)-60))
		}
		return ok("Содержимое "+p+":", strings.Join(out, "\n"))
	})

	add("del", "<путь>", "удалить файл или папку", "файлы", func(a Args) Result {
		p := expandPath(a.str("arg", ""))
		if p == "" || p == "/" || p == "C:\\" {
			return fail("отказ: слишком опасный путь")
		}
		if !fileExists(p) && !dirExists(p) {
			return fail("не найдено: " + p)
		}
		if err := os.RemoveAll(p); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Удалено: " + p)
	})

	// ===== ЗВУК =====
	add("vol", "<0-100>", "установить громкость", "звук", func(a Args) Result {
		v := a.num("arg", 50)
		if v < 0 {
			v = 0
		}
		if v > 100 {
			v = 100
		}
		if isWin() {
			script := `
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class WV {
  [DllImport("winmm.dll")] public static extern int waveOutSetVolume(IntPtr h, uint v);
}'
[WV]::waveOutSetVolume([IntPtr]::Zero, [uint32](` + fmt.Sprint(v*655) + `)) | Out-Null
`
			if _, err := shellOut(script); err != nil {
				return fail("ошибка громкости: " + err.Error())
			}
		} else {
			shellOut("amixer -q sset Master " + strconv.Itoa(v*655) + " 2>/dev/null || pactl set-sink-volume @DEFAULT_SINK@ " + strconv.Itoa(v) + "% 2>/dev/null")
		}
		return ok("Громкость: " + strconv.Itoa(v) + "%")
	})

	add("volget", "", "текущая громкость", "звук", func(a Args) Result {
		if isWin() {
			out, _ := shellOut(`
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class WV {
  [DllImport("winmm.dll")] public static extern int waveOutGetVolume(IntPtr h, out uint v);
}
$u=0; [WV]::waveOutGetVolume([IntPtr]::Zero,[ref]$u) | Out-Null
"Громкость: " + [math]::Round($u/655) + "%"
`)
			return ok("", out)
		}
		out, _ := shellOut("amixer get Master 2>/dev/null | grep -o '\\[[0-9]*%\\]' || pactl get-sink-volume @DEFAULT_SINK@")
		return ok("", out)
	})

	add("volup", "", "громкость +10%", "звук", func(a Args) Result {
		mediaKeyRepeat(0xAF, a.num("arg", 1))
		return ok("Громкость повышена")
	})
	add("voldown", "", "громкость −10%", "звук", func(a Args) Result {
		mediaKeyRepeat(0xAE, a.num("arg", 1))
		return ok("Громкость снижена")
	})
	add("mute", "", "выключить звук", "звук", func(a Args) Result {
		mediaKey(0xAD)
		return ok("Звук переключён")
	})
	add("play", "", "пауза / воспроизведение", "звук", func(a Args) Result {
		mediaKey(0xB3)
		return ok("Плеер переключён")
	})
	add("next", "", "следующий трек", "звук", func(a Args) Result {
		mediaKey(0xB0)
		return ok("Следующий трек")
	})
	add("prev", "", "предыдущий трек", "звук", func(a Args) Result {
		mediaKey(0xB1)
		return ok("Предыдущий трек")
	})

	// ===== ПИТАНИЕ =====
	add("lock", "", "заблокировать экран", "питание", func(a Args) Result {
		if isWin() {
			shellOut("rundll32.exe user32.dll,LockWorkStation")
		} else {
			shellOut("loginctl lock-session 2>/dev/null || xset dpms force off")
		}
		return ok("Экран заблокирован")
	})

	add("sleep", "", "спящий режим", "питание", func(a Args) Result {
		if isWin() {
			shellOut("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
		} else {
			shellOut("systemctl suspend")
		}
		return ok("Переход в сон")
	})

	add("hibernate", "", "гибернация", "питание", func(a Args) Result {
		if isWin() {
			shellOut("shutdown /h")
		} else {
			shellOut("systemctl hibernate")
		}
		return ok("Гибернация")
	})

	add("shutdown", "[задержка_сек]", "выключить компьютер", "питание", func(a Args) Result {
		d := a.num("arg", 5)
		if isWin() {
			if err := run("shutdown", "/s", "/t", strconv.Itoa(d), "/c", "JARVIS: выключение"); err != nil {
				return fail("ошибка: " + err.Error())
			}
		} else {
			shellOut("(sleep " + strconv.Itoa(d) + "; systemctl poweroff) &")
		}
		return ok("Выключение через " + strconv.Itoa(d) + " сек (отмена: shutdown /a)")
	})

	add("restart", "[задержка_сек]", "перезагрузить компьютер", "питание", func(a Args) Result {
		d := a.num("arg", 5)
		if isWin() {
			run("shutdown", "/r", "/t", strconv.Itoa(d), "/c", "JARVIS: перезагрузка")
		} else {
			shellOut("(sleep " + strconv.Itoa(d) + "; systemctl reboot) &")
		}
		return ok("Перезагрузка через " + strconv.Itoa(d) + " сек")
	})

	add("logoff", "", "выйти из профиля", "питание", func(a Args) Result {
		if isWin() {
			run("shutdown", "/l")
		} else {
			shellOut("loginctl terminate-user $USER")
		}
		return ok("Выход из профиля")
	})

	add("cancel", "", "отменить выключение", "питание", func(a Args) Result {
		if isWin() {
			shellOut("shutdown /a")
		}
		return ok("Плановые действия отменены")
	})

	// ===== БУФЕР ОБМЕНА =====
	add("clipget", "", "что в буфере обмена", "буфер", func(a Args) Result {
		if isWin() {
			out, _ := shellOut("Get-Clipboard | Out-String")
			if strings.TrimSpace(out) == "" {
				return ok("Буфер обмена пуст")
			}
			return ok("Буфер обмена:", out)
		}
		out, _ := shellOut("xclip -selection clipboard -o 2>/dev/null || xsel -b")
		return ok("Буфер обмена:", out)
	})

	add("clipset", "<текст>", "скопировать текст в буфер", "буфер", func(a Args) Result {
		t := a.get("arg")
		if t == "" {
			return fail("укажите текст: clipset Привет, сэр")
		}
		if isWin() {
			enc := base64Std(t)
			script := "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Clipboard]::SetText([System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + enc + "')))"
			if _, err := shellOut(script); err != nil {
				return fail("ошибка: " + err.Error())
			}
		} else {
			shellOut("printf %s '" + strings.ReplaceAll(t, "'", "'\\''") + "' | xclip -selection clipboard 2>/dev/null || true")
		}
		return ok("Скопировано: " + t)
	})

	// ===== ПРОЧЕЕ =====
	add("screenshot", "", "сделать скриншот", "прочее", func(a Args) Result {
		var path string
		if isWin() {
			dir := os.Getenv("USERPROFILE") + `\Pictures\Screenshots`
			if !dirExists(dir) {
				dir = os.TempDir()
			}
			path = filepath.Join(dir, "jarvis_"+time.Now().Format("20060102_150405")+".png")
			script := `
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
$b=[System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp=New-Object System.Drawing.Bitmap $b.Width, $b.Height
$g=[System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
$bmp.Save('` + strings.ReplaceAll(path, "'", "''") + `', [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
`
			if _, err := shellOut(script); err != nil {
				return fail("ошибка скриншота: " + err.Error())
			}
		} else {
			dir, _ := os.UserHomeDir()
			dir = filepath.Join(dir, "Pictures", "Screenshots")
			os.MkdirAll(dir, 0755)
			path = filepath.Join(dir, "jarvis_"+time.Now().Format("20060102_150405")+".png")
			if _, err := shellOut("gnome-screenshot -f '" + path + "' 2>/dev/null || scrot '" + path + "' 2>/dev/null || import -window root '" + path + "'"); err != nil {
				return fail("не удалось сделать скриншот")
			}
		}
		if !fileExists(path) {
			return fail("скриншот не сохранён")
		}
		openTarget(filepath.Dir(path))
		return ok("Скриншот сохранён", path)
	})

	add("minimizeall", "", "свернуть все окна", "окна", func(a Args) Result {
		if isWin() {
			shellOut("(New-Object -ComObject Shell.Application).MinimizeAll()")
		} else {
			mediaKey(0x9C) // Super+D
		}
		return ok("Все окна свёрнуты")
	})

	add("restoreall", "", "развернуть окна", "окна", func(a Args) Result {
		if isWin() {
			shellOut("(New-Object -ComObject Shell.Application).UndoMinimizeAll()")
		}
		return ok("Окна восстановлены")
	})

	add("taskbar", "", "показать панель задач", "окна", func(a Args) Result {
		if isWin() {
			shellOut("Start-Process explorer.exe")
		}
		return ok("Панель задач")
	})

	add("emptytrash", "", "очистить корзину", "прочее", func(a Args) Result {
		if isWin() {
			shellOut("Clear-RecycleBin -Force -Confirm:$false")
			return ok("Корзина очищена")
		}
		shellOut("rm -rf ~/.local/share/Trash/files/* 2>/dev/null")
		return ok("Корзина очищена")
	})

	add("openurl", "<ссылка>", "открыть сайт в браузере", "прочее", func(a Args) Result {
		u := a.get("arg")
		if u == "" {
			return fail("укажите ссылку")
		}
		if !strings.HasPrefix(u, "http") {
			u = "https://" + u
		}
		if err := openTarget(u); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Открываю: " + u)
	})

	add("google", "<запрос>", "поиск в Google", "прочее", func(a Args) Result {
		q := a.str("arg", "")
		if q == "" {
			return fail("укажите запрос")
		}
		if err := openTarget("https://www.google.com/search?q=" + urlEncode(q)); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Открываю Google: " + q)
	})

	add("youtube", "<запрос>", "поиск на YouTube", "прочее", func(a Args) Result {
		q := a.str("arg", "")
		if q == "" {
			return fail("укажите запрос")
		}
		if err := openTarget("https://www.youtube.com/results?search_query=" + urlEncode(q)); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Открываю YouTube: " + q)
	})

	add("translate", "<текст>", "перевести текст (→ English)", "прочее", func(a Args) Result {
		t := a.str("arg", "")
		if t == "" {
			return fail("укажите текст")
		}
		openTarget("https://translate.google.com/?sl=ru&tl=en&text=" + urlEncode(t) + "&op=translate")
		return ok("Открываю переводчик")
	})

	add("env", "<имя>", "показать переменную окружения", "прочее", func(a Args) Result {
		n := a.get("arg")
		if n == "" {
			return ok("USERPROFILE/HOME", os.Getenv("HOME")+" | "+os.Getenv("USERPROFILE"))
		}
		return ok(n+" =", os.Getenv(n))
	})

	add("calendar", "", "дата и время", "прочее", func(a Args) Result {
		now := time.Now()
		months := []string{"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"}
		days := []string{"воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"}
		return ok(fmt.Sprintf("%d %s %d, %s", now.Day(), months[int(now.Month())-1], now.Year(), days[int(now.Weekday())]),
			now.Format("15:04:05"))
	})

	add("timer", "<секунды>", "таймер с уведомлением", "прочее", func(a Args) Result {
		sec := a.num("arg", 60)
		go func() {
			time.Sleep(time.Duration(sec) * time.Second)
			beep()
			openTarget("msg " + "JARVIS: таймер завершён")
		}()
		return ok(fmt.Sprintf("Таймер на %d сек запущен", sec))
	})

	add("note", "<текст>", "сохранить заметку", "прочее", func(a Args) Result {
		t := a.get("arg")
		if t == "" {
			return fail("укажите текст заметки")
		}
		dir := filepath.Join(baseDir, "notes")
		os.MkdirAll(dir, 0755)
		f, err := os.OpenFile(filepath.Join(dir, "notes.txt"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			return fail("ошибка: " + err.Error())
		}
		defer f.Close()
		f.WriteString("[" + time.Now().Format("2006-01-02 15:04") + "] " + t + "\n")
		return ok("Заметка сохранена в notes/notes.txt")
	})

	add("notes", "", "показать заметки", "прочее", func(a Args) Result {
		data, err := os.ReadFile(filepath.Join(baseDir, "notes", "notes.txt"))
		if err != nil {
			return fail("заметок пока нет")
		}
		return ok("Заметки:", string(data))
	})

	add("run", "<имя>", "запустить ярлык из shortcuts.json", "запуск", func(a Args) Result {
		n := a.str("arg", "")
		if n == "" {
			shortcutsM.RLock()
			keys := make([]string, 0, len(shortcuts))
			for k := range shortcuts {
				keys = append(keys, k)
			}
			shortcutsM.RUnlock()
			sort.Strings(keys)
			return ok("Доступные ярлыки:", strings.Join(keys, ", "))
		}
		shortcutsM.RLock()
		target, found := shortcuts[strings.ToLower(n)]
		shortcutsM.RUnlock()
		if !found {
			return fail("ярлык «" + n + "» не найден. Список: run")
		}
		if err := openTarget(target); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Запущено: " + n)
	})

	add("reload", "", "перечитать shortcuts.json", "запуск", func(a Args) Result {
		if err := loadShortcuts(); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Ярлыки перезагружены")
	})

	add("browsers", "", "найти установленные браузеры", "запуск", func(a Args) Result {
		list := findBrowsers()
		if len(list) == 0 {
			return fail("браузеры не найдены")
		}
		var lines []string
		for _, b := range list {
			lines = append(lines, "  "+b.Name+" → "+b.Path)
		}
		return ok("Браузеры:", strings.Join(lines, "\n"))
	})

	add("openin", "<браузер> <ссылка>", "открыть ссылку в выбранном браузере", "запуск", func(a Args) Result {
		b := a.get("arg")
		u := a.get("url")
		if b == "" || u == "" {
			return fail("укажите браузер и ссылку")
		}
		if err := openInBrowser(b, u); err != nil {
			return fail("ошибка: " + err.Error())
		}
		return ok("Открыто в " + filepath.Base(b))
	})
}

// ---------- ЗАПУСК ПРОГРАММ ПО ИМЕНИ ----------

func launchByName(n string) Result {
	lower := strings.ToLower(n)
	if isWin() {
		candidates := map[string][]string{
			"блокнот":         {"notepad.exe"},
			"notepad":         {"notepad.exe"},
			"калькулятор":     {"calc.exe"},
			"calc":            {"calc.exe"},
			"проводник":       {"explorer.exe"},
			"explorer":        {"explorer.exe"},
			"cmd":             {"cmd.exe"},
			"терминал":        {"wt.exe", "cmd.exe"},
			"terminal":        {"wt.exe", "cmd.exe"},
			"paint":           {"mspaint.exe"},
			"paint 3d":        {"mspaint.exe"},
			"word":            {"winword.exe"},
			"excel":           {"excel.exe"},
			"taskmgr":         {"taskmgr.exe"},
			"диспетчер задач": {"taskmgr.exe"},
			"параметры":       {"ms-settings:"},
			"настройки":       {"ms-settings:"},
			"store":           {"ms-windows-store:"},
			"магазин":         {"ms-windows-store:"},
			"regedit":         {"regedit.exe"},
			"реестр":          {"regedit.exe"},
			"services":        {"services.msc"},
			"свойства":        {"control.exe"},
			"буфер обмена":    {"control.exe"},
		}
		if paths, found := candidates[lower]; found {
			for _, p := range paths {
				if err := run(p); err == nil {
					return ok("Запущено: " + p)
				}
			}
		}
		// поиск в PATH и стандартных папках
		if out, err := shellOut("where " + n + " 2>$null"); err == nil && strings.TrimSpace(out) != "" {
			first := strings.Split(out, "\n")[0]
			if err := run(first); err == nil {
				return ok("Запущено: " + first)
			}
		}
		// поиск в Program Files
		roots := []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)"), os.Getenv("LOCALAPPDATA")}
		for _, root := range roots {
			if root == "" {
				continue
			}
			_ = filepath.Walk(root, func(p string, info os.FileInfo, err error) error {
				if err != nil || info == nil || info.IsDir() {
					return nil
				}
				ext := strings.ToLower(filepath.Ext(p))
				if (ext == ".exe" || ext == ".lnk") && strings.EqualFold(strings.TrimSuffix(info.Name(), ext), n) {
					run(p)
					return filepath.SkipAll
				}
				return nil
			})
		}
	} else {
		if err := run("xdg-open", lower); err == nil {
			return ok("Запуск: " + lower)
		}
		if out, err := shellOut("command -v " + lower); err == nil && strings.TrimSpace(out) != "" {
			c := exec.Command("nohup", strings.TrimSpace(out), "> /dev/null 2>&1 &")
			if err := c.Start(); err == nil {
				return ok("Запущено: " + strings.TrimSpace(out))
			}
		}
	}
	return fail("не удалось найти программу «" + n + "». Откройте настройки PATH или укажите полный путь.")
}

// ---------- ВСПОМОГАТЕЛЬНОЕ ----------

func beep() {
	if isWin() {
		shellOut("[System.Media.SystemSounds]::Asterisk.Play()")
		return
	}
	shellOut("printf '\\a'")
}

func urlEncode(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.', r == '~':
			b.WriteRune(r)
		case r == ' ':
			b.WriteRune('+')
		default:
			b.WriteString(fmt.Sprintf("%%%02X", r))
		}
	}
	return b.String()
}

const b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

func base64Std(s string) string {
	data := []byte(s)
	var out strings.Builder
	for i := 0; i < len(data); i += 3 {
		var b [3]byte
		n := copy(b[:], data[i:])
		out.WriteByte(b64[b[0]>>2])
		out.WriteByte(b64[(b[0]&0x03)<<4|b[1]>>4])
		if n > 1 {
			out.WriteByte(b64[(b[1]&0x0F)<<2|b[2]>>6])
		} else {
			out.WriteByte('=')
		}
		if n > 2 {
			out.WriteByte(b64[b[2]&0x3F])
		} else {
			out.WriteByte('=')
		}
	}
	return out.String()
}

// ---------- ВЫПОЛНЕНИЕ ----------

func execCommand(name string, a Args) Result {
	name = strings.ToLower(strings.TrimSpace(name))
	fn, found := commands[name]
	if !found {
		return fail("Команда «" + name + "» не найдена. Введите help для списка.")
	}
	defer func() {
		if r := recover(); r != nil {
			log.Printf("[!] panic в %s: %v", name, r)
		}
	}()
	return fn(a)
}

func execCommandJSON(payload string) string {
	var in struct {
		Cmd string `json:"cmd"`
		Arg string `json:"arg"`
		Dir string `json:"dir"`
		URL string `json:"url"`
	}
	if err := json.Unmarshal([]byte(payload), &in); err != nil {
		b, _ := json.Marshal(fail("битый запрос"))
		return string(b)
	}
	res := execCommand(in.Cmd, Args{"arg": in.Arg, "dir": in.Dir, "url": in.URL})
	b, _ := json.Marshal(res)
	return string(b)
}

func commandsListJSON() string {
	b, _ := json.Marshal(commandMeta)
	return string(b)
}
