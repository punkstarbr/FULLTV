# FullTV — plano e validação da remodelação de interface

## Objetivo

Reformular a apresentação do FullTV como uma experiência de TV coesa, clara à distância e previsível no D-pad, mantendo uma adaptação real para celular. A referência é a qualidade de navegação de apps de TV como o TiviMate e os pedidos anteriores do usuário sobre mosaico, foco e controles remotos; não copiar código, assets ou telas proprietárias.

## Princípios não negociáveis

- Preservar destinos, ações, configurações, formatos, filtros, reprodução, gravações, listas, EPG, pareamento, sincronização e controles parentais.
- Manter `Live TV`, português brasileiro como padrão e pt-PT disponível.
- Diferenciar foco D-pad, item selecionado, canal em reprodução e estados de carregamento/erro; não depender somente de cor.
- Oferecer layouts para TV e para celular em retrato/paisagem; evitar larguras e alturas fixas que tornem controles inacessíveis.
- Manter os contrastes e o foco visual atualizados na .42/.43.
- Não alterar persistência, rede, M3U, playback, gravações, EPG funcional ou tratamento do crash durante uma fase exclusivamente visual.
- Para atualizar a instalação existente: manter `applicationId=app.fulltv`, usar exatamente o certificado legado que já assina a instalação e publicar `versionCode > 55`. Isso preserva a possibilidade de atualização por cima, mas mantém o risco operacional conhecido do certificado compartilhado Android Debug.

## Superfícies cobertas

1. Shell, navegação principal, Live TV e portal de categorias.
2. Guia EPG, prévia e mosaico.
3. Player ao vivo, gravações e VOD.
4. Cadastro/pareamento de fontes, gerenciamento de provedores e canais.
5. Configurações, armazenamento, perfis e sincronização.
6. Busca, teclado na tela, semântica e foco transversal.

## Resultado local

O redesign Compose e os componentes compartilhados de chrome/cartão/anel de foco estão implementados no worktree local. Três revisões estáticas independentes das áreas de navegação/EPG, fontes/pareamento e player/gravações/configurações não encontraram regressões concretas nos callbacks ou nos fluxos funcionais. Há uma ressalva de navegação a confirmar em aparelho: no player com largura compacta e controles visíveis, as setas Cima/Baixo navegam a barra de controles; nesse estado não zapeiam canais. O checklist acrescenta essa verificação, sobretudo para Android TV/Fire TV com largura lógica reduzida.

## Validação

- `git diff --check`: passou.
- `:app:compileDebugKotlin`: passou.
- `:app:testDebugUnitTest`: 273 testes, 0 falhas, 0 erros e 0 ignorados.
- `:app:assembleDebug`: passou; esse APK Debug tem identidade/assinatura de debug e não é um update instalável por cima do app Release.
- `:app:lintDebug`: passou após corrigir opt-ins Media3 no helper correto. Permanecem avisos não bloqueantes do projeto.
- Não houve alterações ao package ID, versionName, versionCode, assinatura ou mecanismo do atualizador nesta fase; a base segue `v0.11.43/code55`.
- Nenhum aparelho Android, `adb`, Binder ou KVM foi usado para teste de UI nesta sessão. Não declarar teste Maestro, TV/Fire TV ou Moto G84 concluído.

## Fora de escopo / limites

Esta remodelação não comprova nem corrige o fechamento do FullTV no Moto G84 ou em Box TV. O advisory do lxml continua aberto; a mitigação no parser permanece ativa. A versão pública deve manter `applicationId=app.fulltv`, ter `versionCode` maior que 55 e usar a mesma âncora de assinatura para o atualizador in-app; releases futuras também exigem autorização específica porque a chave é legada/compartilhada.
