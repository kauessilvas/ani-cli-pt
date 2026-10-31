# ani-cli-pt

Uma versão do [ani-cli](https://github.com/pystardust/ani-cli) com foco em legendas em português. Pesquise e reproduza animes direto do terminal, mantendo a interface e o fluxo simples do projeto original.

## O que muda nesta versão

- O modo legendado continua usando o áudio original.
- O ani-cli-pt prefere uma faixa identificada como português ou pt-BR quando ela está disponível no servidor do episódio.
- Se não encontrar português, tenta uma faixa em inglês; se ela não estiver identificada, usa a faixa padrão do servidor.
- Busca, seleção de episódios, histórico, qualidade, player e demais opções continuam seguindo o ani-cli.

A disponibilidade e a identificação das faixas dependem do episódio e do servidor. Por isso, a legenda em português não está disponível em todos os casos.

## Instalação no CachyOS e Arch Linux

Instale os requisitos:

```bash
sudo pacman -S mpv fzf curl grep sed git
```

Clone a branch do ani-cli-pt e inicie o programa:

```bash
git clone --branch ani-cli-pt https://github.com/kauessilvas/ani-cli-pt.git
cd ani-cli-pt
./ani-cli
```

O `mpv` reproduz os vídeos e o `fzf` fornece os menus interativos. O `curl`, `grep` e `sed` são usados pelo script.

## Uso

Abra o menu de busca:

```bash
./ani-cli
```

Ou passe o título diretamente:

```bash
./ani-cli "Attack on Titan"
```

Para selecionar o primeiro resultado e começar pelo episódio 1:

```bash
./ani-cli -S 1 -e 1 "Attack on Titan"
```

Consulte todas as opções disponíveis:

```bash
./ani-cli --help
```

## Opções úteis

| Comando | Ação |
| --- | --- |
| `./ani-cli --dub` | Reproduz a versão dublada, quando disponível |
| `./ani-cli -q 1080 "Naruto"` | Solicita vídeo em 1080p |
| `./ani-cli -d "Naruto"` | Baixa o episódio no diretório atual |
| `./ani-cli --vlc` | Usa o VLC como player |
| `./ani-cli -c` | Continua a partir do histórico |

No mpv, pressione `v` para alternar a exibição das legendas.

## Atualização

Dentro da pasta clonada, atualize a branch com:

```bash
git pull --ff-only
```

## Testar a seleção de legendas

O repositório inclui testes locais para a preferência por pt-BR e os fallbacks:

```bash
sh tests/test-subtitle-selection.sh
```

## Créditos e licença

O ani-cli-pt é um fork do [pystardust/ani-cli](https://github.com/pystardust/ani-cli), cujo código e fluxo servem de base para este projeto. As alterações desta versão concentram-se na seleção de legendas e na experiência em português.

Este projeto mantém a licença GPL-3.0 do ani-cli. Consulte [LICENSE](./LICENSE) e o [aviso de uso](./disclaimer.md).
