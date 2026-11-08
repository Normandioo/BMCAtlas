# HOI4 Style Minecraft Map

Mod **Fabric** (client) para **Minecraft 1.21.11** que abre um mapa em tela cheia no estilo *Hearts of Iron IV*.

## Recursos
- **Físico**: terreno colorido pelas cores de mapa dos blocos, com sombreado por altura. Preenche conforme você explora.
- **Político**: países em cores sólidas, fronteiras escuras e nomes grandes sobre o território.
- **Províncias**: cada província (2×2 chunks = 32×32 blocos) numa cor própria, com fronteiras.
- **Selecionar e clicar**: clique numa província para ver o painel (ID, coordenadas, exploração, dono) e trocar o dono com `<` `>`.
- **Diplomacia** (`4`): clique num país para ver aliados, bloco, suserano/súditos, NAP e inimigos em cores; sem país, mostra os blocos de aliança. A lista do painel permite escolher uma aliança.
- **Guerra** (`5`, botão vermelho com o número de guerras em andamento): lista de guerras (em andamento / encerradas) com barra de quem está ganhando. Dentro de uma guerra, três abas: **Lados** (países de cada lado, cidades seguras e capitulação), **Cidades** (tomadas do inimigo por lado, em disputa/sem controle, e por país em listas recolhíveis; cada cidade leva a câmera até ela) e **Lei 7** (frente por lado, empates à espera da staff, cidades *In Play*, capitulação e legenda). O topo mostra o placar por pontos (Lei 7.9: 150 pontos por reino repartidos entre os portes Small/Medium/Large) e por cidades tomadas, a situação, as datas com contagem regressiva e a trégua de 3 dias depois do fim.
- **Guerra ao vivo**: em guerras marcadas `live` no site, quem segura cada cidade é lido do dynmap (`tiles/_markers_/marker_<mundo>.json`, a cada ~3 s e só com a guerra à vista; o ícone Red/Blue/Green/Contested/Uncontrolled define o dono). Só leitura.
- **Efeitos no mapa**: limites de captura **tracejados** (8 ligado, 5 desligado, sombra escura) na cor de quem segura (amarelo = em disputa, cinza = sem controle); **linhas de frente** grossas com cada metade na cor de quem segura aquela ponta, ligações internas **pontilhadas** em cinza, **anéis pulsantes** nas cidades *In Play*, **espadas** onde há jogadores online dos dois lados no mesmo território e **bandeiras** por 10 min numa cidade recém-tomada.
- **Destaque pulsante**: ao ir a uma cidade (lista de localidades, guerra, senhorios do cartão) ela ganha moldura dourada e anéis por 6 s, e o nome fica sempre visível.
- **Países do site**: com internet, usa o `territories.json` do site (somente leitura): bandeiras, nomes curtos, informações, diplomacia e população.
- **Nomes dentro do território**: cada nome é encaixado na maior área livre do país (inclinação até 30°), como no HOI4.
- **Cidades e limites**: usa os próprios ícones de cidade do site (`icons/<tipo>.png`) e nomes conforme o zoom; clique para ver os dados, o senhor e o limite de captura. No painel do país há a lista de localidades: clique numa para a câmera ir até ela. O botão de limites mostra o contorno de todas as cidades (linha fina e suave, interior levemente mais claro).
- **Mar**: degradê de profundidade em vários tons, manchas de brilho, estrias de onda e espuma na costa.
- **Jogadores online**: botão de jogadores liga/desliga. Mostra a cabeça (do dynmap), a bandeira do país ao lado e o nome ao aproximar; clique abre o cartão. É só um GET ao dynmap (`up/world/<mundo>/<tempo>`) a cada 2 s, só com o mapa aberto; não envia nada ao servidor do jogo e não altera o dynmap. Se o dynmap não responder, mostra só quem o jogo conhece por perto. Ajuste em `site.json`: `dynmapUrl`, `dynmapWorld`, `playersRefreshMs`, `showPlayers`.
- **Panorama diplomático** (botão do pergaminho): Alianças (com detalhes e “ver no mapa”), NAP ativos (com prazo), jogadores prestes a tomar purge (7 dias ou vencidos; filtro para todos; imunes ficam de fora), países diplolocked (fundação, guerra aberta, ultimato pendente, staff), **Tréguas** (as do site, 3 dias depois de uma guerra e depois de um ultimato aceito) e **Ultimatos** pendentes (aceitos sozinhos em 72 h), todos com contagem regressiva.
- **Visual**: molduras de bronze e barra de ícones desenhados à mão, no estilo dos menus do HOI4; a barra quebra em linhas se a janela for estreita.
- **Cartão do jogador**: clique num nome da população (ou no senhor de uma cidade) para ver a skin, se está **online agora** (e onde; clique para ir) ou offline, afiliações, purge e senhorios (clicáveis), com link do NameMC. Quem está online aparece com uma bolinha verde na população do país.
- **Minimapa** na tela do jogo (terreno e países como no mapa grande, cidades, jogadores online e uma seta sua), com tamanho, zoom, canto e o que mostrar ajustáveis.
- **Configurações** (engrenagem): intensidade da cor dos países, opacidade das bordas (de 10 em 10), nomes e estilo dos marcadores das cidades, quando elas aparecem ao aproximar, província sob o cursor, tamanho dos ícones de jogadores (100–400%), guerra ao vivo, espadas/bandeiras, minimapa e escolha do mapa. Salvas em `config/hoi4map/prefs.json`.
- **Vários mapas / outros servidores**: nada é fixo de um servidor. O `site.json` aceita `url` (territories.json), `mapsUrl` (um `maps.json` com várias entradas `key`, `name`, `territories`, `dynmapWorld`, `dynmapUrl`, `dimension` = overworld/nether/end/any, `servers`), `maps` (as mesmas entradas escritas direto no arquivo), `dynmapUrl` e `dynmapWorld`. Com mais de um mapa aparece um botão para trocar; sem escolha, vale o que o `servers` do servidor atual pedir. Sem `url`, o mapa funciona só com os chunks explorados.
- **Gerar chunks**: botão do mapa no canto inferior direito. Mundo local: gera pelo servidor interno sem mover você. Servidor: teleporta você em espectador (`/tp`, exige operador) por todo o mapa e volta no fim.
- Arrastar para mover, roda do mouse para zoom, teclas `1` a `5` trocam o modo, botão direito fecha o painel.

## Uso
- Tecla padrão: **N** (configurável em Controles).
- Países: `config/hoi4map/countries.json` (`id`, `name`, `color` em `#RRGGBB`). Edite e clique em **Recarregar países**.
- Dados por mundo/dimensão (terreno explorado e donos das províncias): `hoi4map/` na pasta do jogo.

## Limite do mapa
Usa a borda do mundo se ela for pequena; senão, a área dos territórios do site (5000×5000 → de -2500 a 2499). Para outro tamanho, defina `worldHalfSize` em `config/hoi4map/site.json`.

## Requisitos
Minecraft 1.21.11, Fabric Loader ≥ 0.18.1, Fabric API.

## Build
O GitHub Actions compila a cada push e publica o `.jar` em **Releases** ao enviar uma tag `v*`.
Localmente: `gradle build` (JDK 21).
