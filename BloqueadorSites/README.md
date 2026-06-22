# 🛡️ Bloqueador de Sites — Android

Bloqueador de sites com proteção contra desinstalação para Android 8.0+.

## Como funciona

| Mecanismo | Descrição |
|---|---|
| **VPN local (DNS)** | Cria uma VPN que intercepta só as queries DNS. Domínios bloqueados recebem NXDOMAIN; os demais são encaminhados normalmente para 8.8.8.8. |
| **Device Admin API** | Enquanto ativo, o Android bloqueia a desinstalação pelo gerenciador de apps. Para remover o app, é preciso primeiro desativar o status de admin manualmente nas Configurações. |
| **PIN de acesso** | Toda gestão (ativar/desativar, adicionar sites, trocar PIN) exige um PIN numérico. Padrão: `1234`. |
| **Auto-start no boot** | Se a VPN estava ativa, é reiniciada automaticamente ao ligar o aparelho. |

> **O bloqueio age no nível DNS**, então funciona em qualquer browser (Chrome, Firefox, etc.) e app que faça consultas DNS normais. Não funciona com apps que usam DNS-over-HTTPS próprio (ex: Cloudflare 1.1.1.1 app).

---

## Pré-requisitos

- **Android Studio** Hedgehog (2023.1) ou superior
- **JDK 11+**
- Android 8.0 (API 26) ou superior no dispositivo/emulador

---

## Como compilar e instalar

### 1. Abrir o projeto

```
File → Open → selecione a pasta BloqueadorSites
```

Aguarde o Gradle sincronizar (download de dependências ~200 MB na 1ª vez).

### 2. Conectar o dispositivo

Ative o **Modo Desenvolvedor** e a **Depuração USB** no celular:

```
Configurações → Sobre o telefone → toque 7x em "Número da versão"
Configurações → Sistema → Opções do desenvolvedor → Depuração USB: ON
```

### 3. Executar

Clique em ▶️ **Run** (ou `Shift+F10`).

---

## Primeiro uso (passo a passo)

1. Abra o app → digitie o PIN `1234`
2. Toque em **"Ativar Proteção Anti-Desinstalação"** → confirme
3. Troque o PIN em **"Alterar PIN de Acesso"**
4. Adicione sites em **"Adicionar Site"** (ex: `instagram.com`, `tiktok.com`)
5. Toque em **"Ativar Bloqueio"** → autorize a VPN quando solicitado

### Resultado

- A VPN fica ativa em segundo plano (ícone de cadeado na barra de status)
- Os sites da lista ficam inacessíveis em qualquer browser
- O app não pode ser desinstalado pelo gerenciador de apps enquanto o Device Admin estiver ativo

---

## Estrutura do projeto

```
app/src/main/
├── AndroidManifest.xml
├── java/com/bloqueador/sites/
│   ├── MainActivity.kt          — UI + lógica de sessão
│   ├── BlockerVpnService.kt     — Serviço VPN + filtragem DNS
│   ├── DnsPacketUtils.kt        — Parser/builder de pacotes IP/UDP/DNS
│   ├── BlockedSitesManager.kt   — CRUD da blocklist (SharedPreferences)
│   ├── PinManager.kt            — Gestão de PIN
│   ├── DeviceAdminReceiver.kt   — Proteção anti-desinstalação
│   └── BootReceiver.kt          — Auto-start após reboot
└── res/
    ├── xml/device_admin.xml     — Política de admin
    └── layout/activity_main.xml — Layout principal
```

---

## Como remover o app (por quem tem acesso)

1. Abra o app e faça login com o PIN
2. Desative o Device Admin:
   **Configurações → Segurança → Administradores de dispositivo → Bloqueador → Desativar**
3. Agora é possível desinstalar normalmente

---

## Limitações conhecidas

- **DNS-over-HTTPS (DoH)**: apps que implementam DoH próprio (Firefox com "DNS Seguro", Cloudflare 1.1.1.1 app) bypassam o bloqueio.
- **VPNs de terceiros**: se o usuário ativar outra VPN, ela pode substituir a nossa.
- **Root**: um dispositivo com root pode bypassar qualquer restrição de nível usuário.
- **Factory reset**: apaga tudo, incluindo o status de admin.

Para controle parental robusto, considere soluções de **MDM (Mobile Device Management)** como Google Family Link ou Microsoft Intune.

---

## Dependências

```
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.constraintlayout:constraintlayout:2.1.4
```
