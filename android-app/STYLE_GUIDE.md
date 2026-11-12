# Guia de estilo do aplicativo Android

Este guia orienta alterações no app Android nativo (`android-app/`). Use-o junto com os componentes em `app/src/main/java/dev/aniclipt/app/ui/`. Em caso de diferença entre este texto e a interface publicada, confirme a intenção no código e atualize o guia na mesma alteração.

As capturas em [`../docs/screenshots/`](../docs/screenshots/) ajudam a revisar a composição geral. Os HTMLs locais em `../referencias-visuais/` são referências de intenção visual, especialmente `player.html` para o agrupamento das configurações; não são componentes do app nem precisam existir em outro checkout.

## Direção visual

- Dar prioridade ao anime e ao episódio: capa, título e ação principal devem ser reconhecíveis antes dos metadados.
- Usar superfícies escuras discretas e um único azul de destaque. Reservar cores fortes para ação, progresso e estados que precisam de atenção.
- Manter a mesma hierarquia em tema claro e escuro. O tema claro troca os tokens, sem inventar outro layout.
- Mostrar apenas informações verificadas. Uma tag `Dublado` ou `Legendado` depende de stream confirmado; quando não houver confirmação, omitir a indicação de idioma.
- Escrever a interface em português do Brasil, com verbos diretos: “Ver episódios”, “Tentar novamente”, “Pular abertura”.

## Cores e tipografia

Use `MaterialTheme.colorScheme` para texto e superfícies. Os tokens de [ui/theme](app/src/main/java/dev/aniclipt/app/ui/theme/Theme.kt) definem o tema do aplicativo.

| Uso | Escuro | Claro | Aplicação |
| --- | --- | --- | --- |
| Fundo | `#111113` | `#F5F5F8` | Tela e áreas fora dos cards |
| Superfície | `#202024` | `#FFFFFF` | Menus, painéis e configurações |
| Texto principal | `#F5F5F7` | `#18181D` | Títulos e ações |
| Texto secundário | `#9A9AA2` | `#575962` | Sinopse curta, contexto e horários |
| Ação principal | `#0A84FF` | `#0064C8` | Seleção, foco e progresso |
| Erro | `#FF8A80` | `#B3261E` | Mensagem de falha e recuperação |

No player, texto e ícones sobre o vídeo são brancos, apoiados por gradiente escuro. O botão de pular trecho usa superfície `#1C1C20` quase opaca, contorno branco sutil e ícone azul. Use cor literal somente quando o elemento precisa manter contraste sobre o vídeo nos dois temas; para o restante, use tokens semânticos.

Use a fonte padrão sans-serif do Android. Escala de referência já aplicada:

| Elemento | Tamanho | Peso e limite |
| --- | --- | --- |
| Título do destaque | `16–30.sp` | Bold; ajustar o tamanho para manter o título inteiro visível, com até 5 linhas no retrato e 3 na paisagem |
| Título de seção | `24.sp` | Bold |
| Título de linha | `15.sp` | SemiBold, até 2 linhas |
| Título de card | `13.sp` | SemiBold ou Bold, até 2 linhas |
| Controles e ação contextual | `13–14.sp` | SemiBold quando primária |
| Metadados | `11–12.sp` | Secundário; não usar para textos longos |

Permita escala de fonte do sistema. Se um título crescer, priorize duas linhas e truncamento controlado em vez de sobrepor ícones ou diminuir a fonte até ficar ilegível.

## Espaçamento, formas e imagens

- Parta de uma escala de `4.dp`: use `8`, `12`, `16`, `20` e `24.dp` para intervalos internos e margens. Não introduza valores novos para casos equivalentes sem motivo visual.
- Margem lateral de conteúdo: `16–20.dp` no retrato; adapte a largura em telas grandes. A área principal atual tem largura máxima de `960.dp`.
- Cabeçalho: `64.dp` de altura. Controles tocáveis devem oferecer área de toque de pelo menos `48.dp`, mesmo quando o ícone visual é menor.
- Cantos: `8.dp` em miniaturas e linhas, `12–14.dp` em cards e busca, `18–22.dp` em painéis e navegação. Use círculo apenas para ícones ou ações isoladas.
- Capas verticais usam proporção aproximada de `2:3`; prévias de episódios e histórico usam `16:9`. Use `ContentScale.Crop` e preserve uma superfície neutra enquanto a imagem carrega.
- O destaque ocupa largura total e recebe gradiente escuro da imagem para o conteúdo. Título, sinopse curta e ação ficam na área de maior contraste, sem depender da cor da capa.
- Áreas clicáveis com forma arredondada devem recortar o ripple na mesma forma.

## Estrutura das telas

### Início e busca

O início segue esta ordem: cabeçalho/busca, carrossel de animes em destaque, “Continue assistindo” quando houver histórico, “Em exibição” e seções complementares como “Populares” e “Em alta agora”. O destaque permite deslizar manualmente entre títulos, sem indicadores de página visíveis, e não avança sozinho. Cada destaque tem uma ação principal (“Ver episódios”) e uma secundária (favoritar). As seções complementares usam trilhos horizontais e omitem títulos já exibidos acima. Mantenha chaves estáveis nas listas e não repita o mesmo anime só para mostrar outra fonte.

A busca mostra resultados em linhas com capa, título e contexto. Ao expandi-la, use um cabeçalho opaco e reserve sua altura antes do conteúdo; textos e resultados não devem aparecer atrás da barra. Com o campo vazio, apresente pesquisas recentes em um painel abaixo da barra, sobre um fundo limpo. Salve apenas buscas confirmadas no teclado ou usadas para abrir um anime; não salve cada trecho digitado. Permita repetir, remover individualmente e limpar o histórico. O painel pode rolar quando houver muitas buscas; a ação de limpar usa ícone e texto em botão neutro arredondado com alvo mínimo de `48.dp`.

A lista do catálogo deve aparecer assim que a resposta básica chegar. Busque arte e metadados adicionais em segundo plano, sem prender o indicador principal nem repetir a consulta do destaque. Limite consultas simultâneas e evite atualizar a lista inteira em todo frame de rolagem. Estados vazios, de erro e de carregamento precisam ter texto claro e ação de recuperação quando couber.

Quando um card entrar na área visível, antecipe a preparação de seus episódios e metadados com concorrência limitada. Ao abrir a seleção, mostre imediatamente os dados validados já guardados no aparelho. Se ainda faltarem arte ou títulos e imagens dos episódios, mantenha o skeleton até a preparação terminar e apresente o resultado de uma vez. A primeira consulta sem cache também usa o skeleton; em falha ou timeout, mostre os dados disponíveis e uma mensagem de erro quando não houver episódios.

### Seleção de episódios

Mostre o título, temporadas, opções de áudio disponíveis e episódios em ordem legível. A linha do episódio é a ação de reprodução; não acrescente um segundo rótulo “Assistir Episódio” a cada item. Ao carregar um episódio, o overlay deve consumir todos os toques para impedir interação com cards atrás dele. Preserve capa e contexto durante troca de temporada até os novos dados chegarem.

Nomeie temporadas pelo lançamento identificado: número explícito quando houver, “Temporada final” e parte quando constarem no título da fonte. Não deduza o número de uma temporada apenas pela posição no menu; se a numeração for desconhecida, mostre o título do lançamento. A arte exibida após carregar a seleção deve pertencer ao lançamento selecionado.

Ao abrir um anime ou trocar de temporada, solicite episódios e arte em paralelo. Compartilhe respostas de detalhe usadas por componentes diferentes para evitar downloads duplicados. Ao iniciar a reprodução, tente primeiro a fonte selecionada quando ela confirmar o áudio ou a legenda desejados; consulte alternativas somente se necessário e limite a simultaneidade dessas consultas.

### Player

- Agrupe **resolução, velocidade e proporção de tela** sob a engrenagem no canto superior direito; deixe o botão de rotação ao lado. O menu abre uma página por opção e mostra claramente o valor selecionado.
- As velocidades expostas no menu atual são `0,25×`, `0,5×`, `1,5×` e `2×`. Mudar essa lista exige revisar o texto e a experiência do menu.
- Mantenha a área central para reproduzir/pausar, recuar/avançar e trocar episódio. A barra de progresso e os tempos ficam na base.
- Mostre “Pular abertura” ou “Pular encerramento” somente dentro de um intervalo válido do AniSkip. No retrato, posicione o botão abaixo do cabeçalho e fora do vídeo/legendas sempre que possível; na paisagem, mantenha-o à direita, acima da barra de reprodução com folga visual.
- Respeite `WindowInsets.displayCutout` e as áreas arredondadas do aparelho em ambos os modos. O botão, menus e ações superiores nunca devem depender da borda física da tela para ter espaço.
- Legendas devem manter contraste e área livre mesmo com controles visíveis. Revise tanto a posição superior quanto a inferior e as opções de estilo personalizadas.
- Ao sair pelo botão Home com vídeo em reprodução, entre em PiP. Mostre apenas vídeo e legendas na janela pequena; use os controles do sistema para pausar ou retomar. Uma reprodução pausada não deve abrir PiP automaticamente.

## Movimento e feedback

- Use transições curtas e consistentes: cerca de `140–230 ms` para menu, seleção e troca de página; movimentos mais longos ficam restritos à entrada de tela ou splash.
- A engrenagem usa rotação de `45°` em `200 ms`. As páginas do menu combinam deslizamento leve e fade (`180 ms` na entrada, `140 ms` na saída). Reaproveite essa linguagem em opções novas.
- Use animação para explicar uma mudança de estado, não para atrasar acesso a uma ação. Evite animar listas inteiras ou imagens grandes em cada atualização de rede.
- Exiba progresso enquanto uma ação de rede está ativa; mostre erro específico e caminho para tentar novamente quando falhar. A tela de carregamento bloqueia ações atrás dela.

## Implementação em Compose

Organize componentes por `ui/home`, `ui/episodes`, `ui/player` e `ui/settings`. Prefira componentes pequenos com parâmetros explícitos, `@Preview` funcional e estado no nível que realmente o usa. `HomeViewModel`, `EpisodeViewModel` e `PlayerViewModel` expõem `StateFlow` e recebem eventos da UI; chamadas de rede e persistência ficam fora dos Composables. A leitura de progresso de rolagem em `graphicsLayer` ou em `derivedStateOf` evita recompor a tela inteira a cada pixel. Requisições de catálogo, imagem e reprodução pertencem ao repositório em `Dispatchers.IO`, com cancelamento quando a tela ou a busca muda.

Dados estruturados de favoritos, histórico, identidade e metadados ficam no Room. Preferências simples de usuário ficam no DataStore. Consultas HTTP compartilham o cliente OkHttp de `HttpTransport`. Migrações de dados legados devem terminar antes de exibir a tela principal.

O `PlaybackService` é dono do único ExoPlayer e da MediaSession. `NativePlayer` conecta um MediaController e anexa o vídeo à UI; não deve criar outro ExoPlayer nem pausá-lo ao entrar em PiP. A notificação de mídia e os comandos de headset usam essa sessão.

Exemplo de ação sobre o vídeo:

```kotlin
@Composable
fun PlayerAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(label, maxLines = 1)
    }
}
```

O exemplo define a área de toque; aplique `windowInsetsPadding(WindowInsets.displayCutout)` no contêiner da tela, uma vez, para não somar margens em cada ação. Escolha `Button`, `IconButton` ou outro componente conforme a função real. Nomeie `contentDescription` de ícones que executam ações; use `null` apenas em ícones decorativos acompanhados por texto. Não derive rótulos de áudio ou disponibilidade de uma imagem, título ou alegação não verificada da fonte.

## Revisão antes de concluir uma mudança

1. Verificar tema escuro e claro, texto ampliado, retrato e paisagem.
2. Conferir áreas arredondadas/recortes, alvos de toque e sobreposição de legendas no aparelho.
3. Revisar carregamento, erro, ausência de dados e ação de voltar; garantir que overlays bloqueiam o conteúdo atrás.
4. Confirmar que tags de áudio, filtros de conteúdo e disponibilidade usam dados verificados.
5. Compilar e executar os testes Android: `./gradlew :app:testDebugUnitTest :app:assembleDebug` em `android-app/`. Testar no aparelho quando a alteração mudar interação, espaçamento ou reprodução.
6. Atualizar este guia se um novo padrão visual ou de interação for adotado.
