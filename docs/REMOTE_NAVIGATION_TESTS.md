# Checklist de navegação por controle remoto

Este roteiro cobre o rail de categorias, o guia Live TV, a Busca com teclado na tela e o player. Os testes unitários validam regras auxiliares; movimentos de D-pad e anúncios de acessibilidade precisam ser confirmados em Android TV/Fire TV ou emulador TV, pois testes Compose instrumentados ainda não estão configurados neste repositório.

## Preparação

- Usar uma lista M3U de teste com canais, favoritos e pelo menos 10 categorias, para que a categoria escolhida possa ficar fora da área visível do rail.
- Para exercitar a seção de provedores, importar duas fontes; para testar modo lista e EPG, selecionar ambos os layouts em momentos separados.
- Se a prévia ao vivo estiver ativada, usar um canal de teste autorizado pelo provedor. Não gravar nem reproduzir conteúdo sem autorização.

## Rail e guia

1. No rail, selecionar uma categoria perto do fim da lista e entrar no guia com **Direita**. O rail deve recolher e o guia deve receber o foco.
2. No primeiro canal/coluna fixa, pressionar **Esquerda**. O rail deve reabrir, rolar até a categoria selecionada mesmo que ela estivesse fora da tela e colocar o foco nela sem exigir um segundo toque.
3. Pressionar **Direita** durante a animação de reabertura. Se o foco entrar novamente no guia, uma solicitação antiga não deve roubar o foco de volta.
4. Mover o foco entre categorias com **Cima/Baixo**. A categoria focada deve ter contorno de foco distinto; ao pressionar **OK**, a seleção deve mudar e continuar anunciada como selecionada por acessibilidade.
5. Com múltiplas fontes, repetir o retorno ao rail com **Todas as fontes** e com uma fonte específica ativa. O foco deve voltar à categoria correspondente, não a uma linha deslocada pelo cabeçalho do provedor.

## Célula de canal e grade EPG

6. No layout de grade, navegar por linhas com **Cima/Baixo** e por programas com **Direita/Esquerda**. O bloco focado deve permanecer visível; mudanças rápidas de direção não devem fazer a faixa voltar a uma posição antiga.
7. Mover o foco para o botão de favorito da célula de canal. O destaque do canal e a prévia devem acompanhar essa linha; **OK** no favorito deve continuar alternando o favorito, sem iniciar playback.
8. Repetir no layout de lista. A célula focada deve continuar destacada quando o foco estiver no botão de favorito interno.
9. Repetir nos estilos de interface disponíveis, especialmente EPG clássico e tela cheia. Confirmar **Voltar/Back** fecha primeiro qualquer menu aberto e não sai do app por baixo de um diálogo.

## Busca, diálogos e retorno de foco

10. A partir do rail, abrir **Busca**; digitar um título de programa que apareça no EPG e conferir se os resultados incluem nome do canal e horário, além de canais/filmes/séries.
11. Focar um resultado de programa e pressionar **OK**. Deve abrir o diálogo de ações de programa já usado no guia, com foco inicial em uma ação válida; não deve iniciar reprodução automaticamente.
12. Pressionar **Voltar** para fechar o diálogo e novamente para retornar à busca. A consulta e os resultados devem continuar presentes, e o foco deve voltar para o resultado que abriu o diálogo.
13. Abrir um diálogo de canal/programa, percorrer ações com o D-pad e fechar. A ação que abriu o diálogo deve recuperar o foco, sem saltar para a primeira ação da tela.
14. Abrir uma opção no hub de configurações, voltar da tela filha e confirmar que o foco regressa à mesma opção. Repetir alternando rapidamente entre rail, busca e guia.

## Busca e teclado na tela

15. Abrir **Busca** e confirmar que o primeiro foco fica na primeira tecla; mover o D-pad por linhas, teclas de ação, lista de resultados e voltar ao último controle focado sem perder o foco.
16. Em uma janela ampla, usar **Direita/Esquerda** para alternar entre teclado e resultados; em celular retrato, verificar que o teclado cabe na largura disponível e que a lista continua rolável logo abaixo dele.
17. Digitar pelo D-pad, inserir espaço, colar texto, apagar um caractere e limpar a consulta; conferir que canais, programas, filmes e séries continuam pesquisáveis e que os resultados acompanham cada alteração.
18. Com resultados de programa, canal, filme e série, confirmar que **OK** executa a mesma ação de antes. Ao voltar de um diálogo/fluxo de programa, a consulta permanece e o foco retorna ao resultado que o abriu.
19. Com TalkBack/serviço compatível, verificar que as teclas e ações anunciam rótulo e papel de botão; títulos de seção são headings; cada resultado anuncia seus textos visíveis; ícones e logos decorativos não criam anúncios duplicados.
20. Testar **Concluído** e **Voltar** com o controle remoto. Ambos devem sair da Busca pelo fluxo já existente, sem limpar a consulta como efeito colateral.

## Player e telas compactas

21. Em tela cheia, testar **Cima/Baixo** com os controles ocultos: deve trocar de canal e mostrar o banner; testar também as teclas dedicadas ChannelUp/ChannelDown, se existirem.
22. Com os controles visíveis e viewport lógico abaixo de 1100 dp, o D-pad **Cima/Baixo** passa a navegar pelas linhas dos controles e não zapeia. Confirmar se isso é previsível e se ainda há caminho fácil para mudar de canal no dispositivo testado.
23. Repetir em Android TV/Fire TV de 720p/1080p, incluindo densidade alta que reduza a largura em dp. Verificar foco em botões de múltiplas linhas, botão de lista de canais, **Back**, áudio/legendas e retorno ao vídeo.
24. No estilo de informação superior, verificar a navegação vertical entre as abas superiores e os controles inferiores; confirmar que a troca de canal continua acessível pelo painel/lista.

## Acessibilidade e regressão

25. Com TalkBack/serviço de acessibilidade compatível, verificar se o rail anuncia item selecionado; cada canal anuncia o programa atual; cada bloco EPG anuncia título, canal, horário e se está no ar ou é próximo.
26. Alternar categorias/fontes enquanto navega e verificar que uma categoria removida/ocultada não deixa o foco preso num item inexistente; o fallback visual deve ser **Todos**.
27. Repetir rapidamente a sequência Esquerda/Direita várias vezes e confirmar que não há foco perdido, animação antiga em disputa ou crash.

Registrar modelo do dispositivo, versão Android/Fire OS, largura em dp, layout (grade/lista), estilo de interface e qualquer resultado diferente do esperado. Esta sessão não teve aparelho conectado; não marcar estes passos como executados até testá-los fisicamente.
