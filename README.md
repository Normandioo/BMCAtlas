# HOI4 Style Minecraft Map

Mod **Fabric** (client) para **Minecraft 1.21.11** que abre um mapa em tela cheia no estilo *Hearts of Iron IV*.

## Recursos
- **Físico**: terreno colorido pelas cores de mapa dos blocos, com sombreado por altura. Preenche conforme você explora.
- **Político**: países em cores sólidas, fronteiras escuras e nomes grandes sobre o território.
- **Províncias**: cada província (2×2 chunks = 32×32 blocos) numa cor própria, com fronteiras.
- **Selecionar e clicar**: clique numa província para ver o painel (ID, coordenadas, exploração, dono) e trocar o dono com `<` `>`.
- **Diplomacia** (`4`): clique num país para ver aliados, bloco, suserano/súditos, NAP e inimigos em cores; sem país, mostra os blocos de aliança. A lista do painel permite escolher uma aliança.
- **Guerra** (`5`): lados de cada guerra do site, cidades tomadas/em disputa e limites de captura na cor de quem os controla.
- **Países do site**: com internet, usa o `territories.json` do site (somente leitura): bandeiras, nomes curtos, informações, diplomacia e população.
- **Nomes dentro do território**: cada nome é encaixado na maior área livre do país (inclinação até 30°), como no HOI4.
- **Cidades e limites**: ícones por porte (capitais com borda dourada) e nomes conforme o zoom; clique para ver os dados, o senhor e o limite de captura. Botão de limites mostra o contorno de todas as cidades.
- **Cartão do jogador**: clique num nome da população (ou no senhor de uma cidade) para ver a skin, afiliações, purge e senhorios, com link do NameMC.
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
