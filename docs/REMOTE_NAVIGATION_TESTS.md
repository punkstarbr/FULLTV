# Checklist de navegação por controle remoto

Este roteiro cobre o comportamento do rail de categorias e do guia Live TV. Os testes unitários validam o índice do item a focar; os movimentos de D-pad precisam ser confirmados em Android TV/Fire TV ou emulador TV, pois testes Compose instrumentados ainda não estão configurados neste repositório.

## Preparação

- Usar uma lista M3U de teste com canais, favoritos e pelo menos 10 categorias, para que a categoria escolhida possa ficar fora da área visível do rail.
- Para exercitar a seção de provedores, importar duas fontes; para testar o modo lista e o EPG, selecionar ambos os layouts em momentos separados.
- Se a prévia ao vivo estiver ativada, usar um canal de teste autorizado pelo provedor. Não gravar nem reproduzir conteúdo sem autorização.

## Rail e guia

1. No rail, selecionar uma categoria perto do fim da lista e entrar no guia com **Direita**. O rail deve recolher e o guia deve receber o foco.
2. No primeiro canal/coluna fixa, pressionar **Esquerda**. O rail deve reabrir, rolar até a categoria selecionada mesmo que ela estivesse fora da tela e colocar o foco nela sem exigir um segundo toque.
3. Pressionar **Direita** imediatamente durante a animação de reabertura. Se o foco entrar novamente no guia, a solicitação antiga não deve roubar o foco de volta.
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
13. Abrir um diálogo do canal/programa, percorrer ações com o D-pad e fechar. A ação que abriu o diálogo deve recuperar o foco, sem saltar para a primeira ação da tela.
14. Abrir uma opção no hub de configurações, voltar da tela filha e confirmar que o foco regressa à mesma opção. Repetir alternando rapidamente entre rail, busca e guia.

## Acessibilidade e regressão

15. Com TalkBack/serviço de acessibilidade compatível, verificar se a rail anuncia item selecionado; cada canal anuncia o programa atual; cada bloco EPG anuncia título, canal, horário e se está no ar ou é próximo.
16. Alternar categorias/fontes enquanto navega e verificar que uma categoria removida/ocultada não deixa o foco preso num item inexistente; o fallback visual deve ser **Todos**.
17. Repetir rapidamente a sequência Esquerda/Direita várias vezes e confirmar que não há foco perdido, animação antiga em disputa ou crash.

Registrar modelo do dispositivo, versão Android/Fire OS, layout (grade/lista), estilo de interface e qualquer resultado diferente do esperado. Esta sessão não teve aparelho conectado; não marcar estes passos como executados até testá-los fisicamente.
