# No Reels

App Android pessoal que tira "Reels", "Pesquisa" e anúncios do Instagram via serviço de acessibilidade. Sem root.

## Como usar

Requer Android 14+ com depuração ativa e JDK 17 para compilar.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Depois ative em **Configurações > Acessibilidade > No Reels**.

## Se parar de funcionar

Uma atualização do Instagram pode mudar os IDs que o app usa. Gere um diagnóstico em **Avançado** e ajuste os IDs lá mesmo.
