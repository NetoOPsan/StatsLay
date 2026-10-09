# G9 Overlay

Overlay de métricas com root para o Moto G9 Play (testado só em simulação; veja "Limites").

## Métricas (ligue/desligue tocando no overlay)
RAM · ZRAM (usado, RAM real, taxa de compressão) · Swap em disco · Swap I/O (MB/s) · Pressão de memória (PSI) ·
CPU % (total, silver, gold) · clock de cada núcleo (MHz) · GPU (MHz e %) · Temperaturas (CPU, GPU, PCB, bateria) ·
Bateria · App em foco (RAM e swap dele) · FPS (experimental) · Limites/throttling · custo do próprio overlay.

## Como compilar

### Opção A: GitHub Actions (sem instalar nada)
1. Crie um repositório no GitHub e envie TODOS os arquivos desta pasta (inclusive `.github/`).
2. Aba **Actions** -> **Build APK** -> **Run workflow** (ou faça um push na branch main).
3. Ao terminar, baixe o artefato **G9Overlay-debug-apk** e instale o `app-debug.apk`.

### Opção B: Android Studio
Abra esta pasta como projeto, espere o Gradle sincronizar e use **Build > Build APK(s)**.

## Primeiro uso
1. Instale o APK e abra o app.
2. Toque em **1) Conceder permissões (root)** e aceite o aviso do KernelSU.
   Ele libera "sobrepor apps", "rodar em segundo plano" e tira o app da economia de bateria.
3. Toque em **2) Iniciar overlay**.
4. Toque no overlay para abrir o painel (ligar/desligar métricas, A-/A+, Fechar, Parar). Arraste para mover.

## "Prioridade absoluta"
A cada ciclo o app grava -1000 no `oom_score_adj` do próprio processo (via root). Com -1000 o lmkd e o
OOM killer do kernel não matam o processo. O Android reescreve esse valor de tempos em tempos, então
regravamos todo ciclo (pode haver uma janela de até 1 ciclo).

## Custo
Um shell root fica aberto e recebe UMA linha de comando por ciclo, só com as métricas ligadas, usando
comandos internos do shell (sem criar processos), exceto: varredura do app em foco (a cada 5 ciclos) e FPS
(`dumpsys`, só se ligado). Ative "Uso do overlay" para ver o custo real no seu aparelho.

## Limites
- O código foi compilado contra a API do Android 29 e a lógica de leitura testada com saídas simuladas,
  mas o projeto Gradle e o app NÃO foram executados num celular. O primeiro build pode precisar de ajustes.
- FPS: estimado por `dumpsys SurfaceFlinger --latency`; depende da versão do Android e do nome da camada.
- PSI só aparece se o kernel expuser `/proc/pressure/memory`.
- Nomes de arquivos do sysfs (GPU, zonas térmicas) podem variar; o que não existir aparece como n/d.
