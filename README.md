# HOI4 Style Minecraft Map

Mod **Fabric** (client) para **Minecraft 1.21.11** que abre um mapa em tela cheia no estilo *Hearts of Iron IV*.

## Recursos
- **Físico**: terreno colorido pelas cores de mapa dos blocos, com sombreado por altura. Preenche conforme você explora.
- **Político**: países em cores sólidas, fronteiras escuras e nomes grandes sobre o território.
- **Províncias**: cada província (2×2 chunks = 32×32 blocos) numa cor própria, com fronteiras.
- **Selecionar e clicar**: clique numa província para ver o painel (ID, coordenadas, exploração, dono) e trocar o dono com `<` `>`.
- Arrastar para mover, roda do mouse para zoom, teclas `1` `2` `3` trocam o modo.

## Uso
- Tecla padrão: **N** (configurável em Controles).
- Países: `config/hoi4map/countries.json` (`id`, `name`, `color` em `#RRGGBB`). Edite e clique em **Recarregar países**.
- Dados por mundo/dimensão (terreno explorado e donos das províncias): `hoi4map/` na pasta do jogo.

## Requisitos
Minecraft 1.21.11, Fabric Loader ≥ 0.18.1, Fabric API.

## Build
O GitHub Actions compila a cada push e publica o `.jar` em **Releases** ao enviar uma tag `v*`.
Localmente: `gradle build` (JDK 21).
