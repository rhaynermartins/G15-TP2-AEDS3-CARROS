# TP2 — Fase 1: árvore B+ e infraestrutura

Base: TP1 aprovado, commit `9590f2e5394069e5cef19a769ba8d721d9fc78df`.
Repositório desta evolução: `rhaynermartins/G15-TP2-AEDS3-CARROS`.
A main conserva a base do TP1; a Fase 1 está em `checkpoint/tp2-fase-1`
para revisão. Hashing e listas invertidas pertencem à próxima fase.

## Escolha e convenção

A B+ separa navegação e entradas: páginas internas direcionam a busca;
folhas guardam `int id -> long posição`. O encadeamento das folhas permite
percurso crescente e pode apoiar consultas por faixa futuramente.

Ordem **m = máximo de filhos internos**. Tanto folhas quanto páginas internas
guardam até m-1 chaves. Fora a raiz, folhas mantêm pelo menos floor(m/2)
entradas; páginas internas mantêm ceil(m/2) filhos. Todas as folhas têm a mesma
profundidade. O separador interno é o menor ID da subárvore à direita.

O usuário informa m ao criar/reconstruir. São aceitos valores de 3 a 4096:
3 é o mínimo estrutural, e o limite superior evita alocações excessivas por
entrada inválida. A ordem é gravada no arquivo e recuperada na reabertura.
Nos testes pequenos são usadas ordens 3 a 9; na base real, 32.

## Formato binário da B+

Arquivo gerado: `data/index/arvore_bplus.idx`. Números em big-endian.

| Offset | Tipo | Conteúdo do cabeçalho |
|---|---|---|
| 0 | int | magic 0x42504C53 |
| 4 | int | versão 1 |
| 8 | int | ordem m |
| 12 | int | tamanho de página |
| 16 | long | offset da raiz |
| 24 | long | offset da primeira folha |
| 32 | long | quantidade de páginas alocadas |
| 40 | long | quantidade de entradas |
| 48 | long | primeira página livre; -1 se ausente |
| 56 | long | operação pendente: 0 ou 1 |

Cabeçalho: 64 bytes. Página: `13 + 4*(m-1) + 8*m` bytes.

| Offset na página | Tipo | Conteúdo |
|---|---|---|
| 0 | byte | 0 interna; 1 folha |
| 1 | int | quantidade de chaves válidas |
| 5 | long | próxima folha; -1 se ausente/interna |
| 13 | int[m-1] | chaves; só as primeiras n são válidas |
| 13+4*(m-1) | long[m] | filhos internos (n+1) ou posições de dados (n) |

Todos os ponteiros de página são offsets absolutos alinhados, a partir de 64.
Uma página liberada usa tipo 2 no byte 0 e o offset da próxima livre no byte 1.
Páginas liberadas por fusão/redução da raiz são reaproveitadas em alocações.

No split de folha, a menor chave da nova folha direita é copiada para o pai;
as entradas permanecem nas folhas. No split interno, o separador é promovido
e retirado dos filhos. Remoções tentam redistribuição antes da fusão;
separadores e encadeamento são atualizados, inclusive na redução da raiz.

## Integração com os dados do TP1

O formato de `dados.db` não mudou: `[int ultimoId]` seguido de
`[byte lápide][int tamanho][objeto]`. A posição guardada na B+ é o endereço
da **lápide**. A leitura indexada faz seek nesse endereço e valida lápide e ID.
Ela não chama o Read sequencial. `createComPosicao`, `readAtPosition`,
`updateAtPosition`, `deleteAtPosition` e `percorrerAtivos` complementam o DAO.

O serviço `GerenciadorIndices` oferece Create/Read/Update/Delete controlados
pela B+. Update de mesmo tamanho conserva a posição; tamanho diferente anexa
a nova versão e atualiza o índice. Delete marca lápide e remove a entrada.
A reconstrução percorre somente registros ativos em fluxo, sem exigir CSV
e sem manter a árvore inteira em memória. IDs ativos duplicados são recusados.

O menu principal conserva as opções do TP1 e acrescenta a opção 9:
criar/reconstruir, buscar, informar, validar e comparar leituras.
As alterações sequenciais/carga feitas pelo menu reconstroem a B+ quando
ela está ativa. É uma estratégia simples desta fase, com custo linear na
reconstrução; o CRUD direto do serviço atualiza entradas individualmente.
As duas ordenações passam pelo serviço, que recompõe os endereços após
sucesso. O algoritmo original de ordenação não foi alterado.

## Coerência e limites

Antes de alterar os dados, o serviço sincroniza um marcador `.idx.pendente`
em disco. Após fechar o índice com sucesso, grava `.idx.meta` (caminho real,
identidade do arquivo, tamanho e instante de modificação) e remove o marcador.
A reconstrução usa um arquivo temporário e substitui o índice somente após
validá-lo. Uma falha mantém o marcador e bloqueia consultas até reconstrução.
O cabeçalho da árvore também indica operações internas incompletas.

A assinatura detecta alterações externas usuais, inclusive mesmo tamanho,
e troca de arquivo. Não é um hash criptográfico do conteúdo: manipulação
externa que preserve todos esses metadados exige validação completa. A opção
Validar confere os dois sentidos: todas as folhas contra os dados e todos os
registros ativos contra a árvore, além de contagens, limites, separadores,
profundidades, duplicidades, ciclos e páginas livres/órfãs.

Uso previsto: uma aplicação/escritor por vez. Não há transações com rollback,
controle de concorrência entre processos ou garantia contra toda falha de
energia. Se ocorrer interrupção, preserve os dados e reconstrua o índice.
Não altere os arquivos binários manualmente durante a execução.
Índices, metadados e temporários em `data/index/` não são versionados.

## Compilação e testes

O projeto original usa javac, sem Maven/pom.xml. A tentativa exigida de
`mvn clean compile` retorna MissingProjectException; não foi introduzido
outro sistema de build. Java 11+ é necessário; validação em OpenJDK 25.0.2.

```sh
javac -encoding UTF-8 -d out -sourcepath src src/*.java
java -cp out TesteTP1
java -cp out TesteBPlus
java -cp out TesteTP1 --base-100k
java -cp out TesteBPlus --base-real
java -cp out Main
```

No Windows, `compilar.bat` compila as dependências e os testes novos.
`TesteTP1 --base-100k` **recria dados.db a partir do CSV**, como já fazia no TP1;
execute apenas em cópia de teste ou sem dados locais a preservar.
`TesteBPlus --base-real` não recarrega nem altera os dados: reconstrói o índice
de ordem 32, compara todas as entradas e consulta amostras da base encontrada.
Os testes pequenos ficam exclusivamente em `build-test/`.

## Resultados de 25/09/2026

- Regressão TP1: carga, CRUD, ambos os updates, exclusão, duas ordenações e
  CRUD posterior aprovados; cenário comum 59 registros/9 runs/2 passagens;
  seleção 17 registros/2 runs/1 passagem/5 congelados.
- Base real TP1: 100.000 importados, 0 ignorados; 4.617 ms de carga e
  5.829 ms de ordenação; 100 runs e 4 passagens; 0 IDs duplicados.
- B+: ordens 3 a 9 com splits internos/folhas, crescimento/redução de raiz,
  persistência, redistribuição, fusão, árvore vazia e reúso de páginas aprovados.
- 1.200 operações intercaladas conferidas com oráculo independente.
- Dez corrupções de arquivo recusadas. CRUD direto, reconstrução sem CSV,
  ambas as ordenações e recuperação após falha simulada aprovados.
- Base ativa atual: 100.000; cabeçalho ultimoId=100001 após o Create/Delete
  de teste do TP1. As duas versões excluídas desse ID não entram no índice.
- B+: 100.000 entradas, ordem 32, altura 4, 6.250 folhas, 6.639 páginas,
  página de 393 bytes, arquivo de **2.609.191 bytes**.
- Construção: **2.551.738.875 ns (2,552 s)**.
- Amostras: 1, 2, 50000, 99999, 100000 e 100001 (ausente).
- Mesmas 18 consultas: sequencial 6.095.472.582 ns; B+ 4.226.876 ns.
  Medição local simples, sujeita a cache e ambiente; não generaliza desempenho.
- Nova execução do Main abriu o índice persistido, exibiu informações,
  encontrou 50000 e informou 100001 como não encontrado.

## Pontos do enunciado e próximas fases

O PDF fornecido diz 12/04/2025; o cabeçalho informa 5 pontos, mas os critérios
listam implementação 5 e vídeo 1. Confirmar com o professor data/pontuação
vigentes. Há ainda o rótulo "Descrição do TP1" em um documento de TP2.
A escolha B+ atende tanto à etapa destacada quanto à lista B/B+/B* permitida.

Fase 2, somente após autorização: Hashing Estendido (ID, h(k)=k mod 2^p,
bucket de 5% da base inicial real) e dois arquivos de listas invertidas,
preferencialmente ano/características, com pesquisa combinada.
Fase 3: integração de todos os índices e documentação final no README.
O vídeo TP1 permanece preservado; o vídeo TP2 é uma etapa posterior.

**Estado da Fase 1: aguardando revisão humana.**
