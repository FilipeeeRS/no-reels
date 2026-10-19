# No Reels

App Android de uso pessoal (sideload) que tira o Reels, a Pesquisa e anúncios do Instagram usando um serviço de acessibilidade. Não precisa de root e não envia nada para fora do aparelho.

## O que faz

**Esconde ícones da barra inferior**
- Esconder ícone do Reels
- Esconder ícone da Pesquisa

A barra do Instagram é coberta por um overlay que redesenha só as abas mantidas (Início, Mensagens, Perfil), centralizadas. Os ícones são recortes da barra real, então o destaque da aba selecionada e a foto de perfil continuam os de verdade. Toques são repassados para as abas reais.

**Telas**
- Sair da tela do Reels: ao chegar no Reels (inclusive arrastando o feed), pula para a próxima aba visível ou volta para o início.
- Sair da tela de Pesquisa: o mesmo para a Pesquisa.
- Sair de Reels mandados por amigos: fecha Reels abertos fora da aba Reels (mensagens, links, posts do feed).

Durante o arraste, o conteúdo das abas bloqueadas fica coberto e o som de mídia é silenciado por um instante.

**Anúncios**
- Pular anúncios nos Stories: toca no lado direito ao detectar "story patrocinado".
- Pular anúncios nos Reels: rola para o próximo vídeo ao detectar "Patrocinado". *(não testado)*

Há ainda uma chave geral no topo que pausa tudo sem desligar a acessibilidade.

## Requisitos

- Android 14 ou mais novo (usa `takeScreenshotOfWindow`)
- Para compilar: Android SDK (platform 35) e JDK 17

## Compilar

O build precisa do JDK 17. O Gradle 8.11 não roda no JDK 25.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar

Com a Depuração USB ligada e o USB em "Transferência de arquivos":

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Depois ative em **Configurações > Acessibilidade > Apps instalados > No Reels**.

Se instalar abrindo o APK no celular (sem ADB), o Android bloqueia a acessibilidade. Libere em **Configurações > Apps > No Reels > ⋮ > Permitir configurações restritas**.

## Quando o Instagram atualizar

O app depende de IDs internos do Instagram (`tab_bar`, `clips_tab`, `search_tab`, `feed_tab`, `swipeable_tab_view_pager`, `clips_viewer_view_pager`, `reel_viewer_header`). Se uma atualização mudar algum deles, a função correspondente para de agir.

1. Gere um diagnóstico com o Instagram aberto na tela que falhou. Pelo app, use **Avançado > Gerar diagnóstico** e depois abra o Instagram. Pelo computador:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am broadcast -a dev.semreels.DUMP -p dev.semreels
   ```
2. O arquivo fica em `/sdcard/Android/data/dev.semreels/files/dumps/` e também pode ser copiado em **Avançado > Copiar**.
3. Procure o novo ID ou rótulo e ajuste em **Avançado**: IDs da barra, do Reels, da Pesquisa e rótulos de anúncio. Para isso não precisa recompilar.

Os IDs que não aparecem em **Avançado** ficam em [Prefs.kt](app/src/main/java/dev/semreels/Prefs.kt) e pedem recompilação.

## Limitações

- O Reels ou a Pesquisa ainda podem piscar por um ou dois quadros no começo do arraste.
- Não dá para desativar o arraste entre abas. O Instagram não permite bloquear o gesto, só desfazê-lo depois.
- Anúncios do feed não são tratados.
- Silenciar a mídia durante o arraste também corta por um instante qualquer música tocando em segundo plano.

## Estrutura

| Arquivo | Papel |
|---|---|
| `ReelsBlockerService.kt` | Serviço de acessibilidade: avalia a tela, redireciona abas, pula anúncios, captura a barra |
| `BarFinder.kt` | Localiza a barra inferior e classifica as abas |
| `BarOverlay.kt` | Overlay que redesenha a barra e repassa toques |
| `PageMask.kt` | Cobertura do conteúdo durante o arraste |
| `Prefs.kt` | Chaves de configuração e IDs do Instagram |
| `MainActivity.kt` | Tela do app |
| `TreeDumper.kt` | Gera o diagnóstico da árvore de views |
