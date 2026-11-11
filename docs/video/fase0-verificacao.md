# Plano de vídeo, Fase 0: verificação das APIs

Feita em 2026-11-10 sobre a `main` no commit `963e757` (versão 1.2.6). Responde aos pontos marcados **a verificar** nas seções 8.5, 9.1 e 10 do `bmc-atlas-plano-video.md` (revisão 3).

## Como foi verificado

- **1.21.11:** o jar do Minecraft com os nomes da Mojang que o Loom gera (`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-.../*.jar`).
- **26.1.2, 26.2 e 26.3:** os jars do cliente baixados da Mojang a partir do manifesto oficial (`piston-meta.mojang.com/mc/game/version_manifest_v2.json`), com o SHA-1 conferido. Essas versões já vêm sem ofuscação.
- `javap` do JDK 21 (consegue ler as classes do Java 25), com `-p` para membros privados e `-c` para ver a ordem das chamadas.
- Os métodos escolhidos foram escritos no `Compat` das duas versões (seção "Compat" abaixo). O CI compila a 26.x contra 26.1.2, 26.2 e 26.3, o que confirma as assinaturas na prática.

## Resultado

| Ponto | 1.21.11 | 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|---|
| `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` | sim | sim | sim | sim |
| Alvo principal (o que o F2 lê) | `Minecraft.getMainRenderTarget()` | `Minecraft.getMainRenderTarget()` | **`GameRenderer.mainRenderTarget()`** | **`GameRenderer.mainRenderTarget()`** |
| Fila que sempre enfileira: `BlockableEventLoop.schedule(R)` | sim | sim | sim | sim |
| Pixels em bloco: `NativeImage.getPixelsABGR()` e `getPixels()` | sim | sim | sim | sim |
| Shift no evento de tecla: `KeyEvent` implementa `InputWithModifiers.hasShiftDown()` | sim | sim | sim | sim |

### Alvo principal

- Na 26.2 e na 26.3, `Minecraft.getMainRenderTarget()` **não existe mais**. O alvo foi para `GameRenderer.mainRenderTarget()`.
- O F2 dessas versões (`Screenshot.grab(Minecraft, boolean)`) lê justamente `mc.gameRenderer.mainRenderTarget()`, conferido no bytecode. Então o caminho do plano continua valendo: capturar o mesmo alvo que o F2.
- A 26.3 tem também um `hud3DTarget` no `GameRenderer`. Como o F2 dela continua lendo o `mainRenderTarget`, a suposição é que a interface termina composta nele. **Só o jogo confirma** que o mapa aparece na captura da 26.3.
- Como o jar 26.x é um só para as três versões, o `Compat` da 26.x procura o método em tempo de execução: primeiro `Minecraft.getMainRenderTarget`, senão `GameRenderer.mainRenderTarget`.

### Momento da captura (seção 9.1)

- `Minecraft.execute(Runnable)` **não serve**: chamado da thread do jogo, roda na hora.
- `BlockableEventLoop.schedule(R)` sempre põe na fila. Essa é a escolha (`Compat.afterFrame`).
- Ordem dentro de `Minecraft.runTick` na 1.21.11, conferida no bytecode:
  1. `runAllTasks()`: as tarefas da fila;
  2. `clearColorAndDepthTextures` no alvo principal;
  3. `GameRenderer.render`: mundo e interface;
  4. `RenderTarget.blitToScreen()` e `Window.updateDisplay`.
- Na 26.3 a ordem começa igual: `runAllTasks()` e depois `renderFrame`.
- Consequência: uma captura enfileirada durante o quadro N roda no começo do quadro N+1, **antes** da limpeza, e lê o quadro N completo, com a interface. É o comportamento que o plano queria; a reserva por `START_CLIENT_TICK` (limitada a 20 por segundo) não é necessária.
- `takeScreenshot` é assíncrono: a GPU copia a imagem, e o `Consumer` recebe o `NativeImage` depois. Quem recebe é dono da imagem e precisa fechá-la (`close()`).

### Pixels

- `getPixelsABGR()` devolve o array inteiro como está guardado, sem conversão; `getPixels()` também existe nas quatro versões, já convertido. A escolha é `getPixelsABGR()` (`Compat.pixels`), com a conversão para RGBA feita na thread do codificador.
- **A verificar no jogo:** se a imagem que o `takeScreenshot` entrega já vem com a primeira linha no topo (o plano supõe que sim, porque o arquivo do F2 sai certo).

### Shift (seção 8.5)

- `KeyEvent` tem `hasShiftDown()` nas quatro versões, pela interface `InputWithModifiers`. **Não precisa de `Compat`.**

## Compat

Métodos acrescentados nesta fase. Nada os chama ainda; eles existem para a Fase 4 e para o CI provar as assinaturas:

| Método | 1.21.11 (`src/.../Compat.java`) | 26.x (`port26/Compat.java`) |
|---|---|---|
| `mainTarget(Minecraft)` | `mc.getMainRenderTarget()` | Reflexão: `Minecraft.getMainRenderTarget`, senão `GameRenderer.mainRenderTarget` |
| `grabFrame(Minecraft, Consumer<NativeImage>)` | `Screenshot.takeScreenshot(mainTarget(mc), done)` | Igual, se o alvo foi encontrado |
| `afterFrame(Minecraft, Runnable)` | `mc.schedule(task)` | Igual |
| `pixels(NativeImage)` | `image.getPixelsABGR()` | Igual |

## O que continua dependendo do jogo

1. A captura agendada com `afterFrame` pega o quadro com o mapa e a interface, sem quadro repetido nem faltando.
2. Na 26.3, o mapa aparece no `mainRenderTarget` apesar do `hud3DTarget`.
3. A orientação da imagem (primeira linha no topo).

A Fase 0 não mostrou nada que mude a seção 9 do plano: a captura funciona como ele supõe, com a única adaptação do alvo principal na 26.2 e na 26.3, que fica no `Compat`.
