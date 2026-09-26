# TP2 — Fase 2: Hashing Estendido e listas invertidas

Base: Fase 1 aprovada, commit `e9e66563581d2bbf6631d2e882c5c43ebd017a09`,
incorporada por fast-forward à main. Desenvolvimento em `checkpoint/tp2-fase-2`;
sem merge desta fase. Repositório: `rhaynermartins/G15-TP2-AEDS3-CARROS`.

## Escopo e preservação

Acrescentados Hash, duas listas, serviço auxiliar e testes. Dos arquivos anteriores,
somente `GerenciadorIndices.java` (coordenação/recuperação) e `Main.java`
(consultas no submenu) precisaram de integração. A classe B+, seu formato,
algoritmos, ordem e testes não mudaram. DAO, Carro, importador, ordenações,
CSV, README e build foram preservados. Não há rotas web, tabelas ou SQL.

## Hash persistente

- Chave `int id`, valor `long posição` do byte da lápide ativa em `dados.db`.
- Função explícita `id % (1L << p)`, isto é, **h(k) = k mod 2^p**.
- Profundidade global inicial 1; dois buckets com profundidade local 1.
- Capacidade calculada por `ceil(N_inicial / 20)`, mínimo 1. Para a base oficial:
  **100000 → 5000 entradas**, não bytes. Nos testes controlados: 40 → 2.
- `N_inicial` é recebido na ativação, persistido no cabeçalho e em `fase2.meta`.
  Reconstruções reutilizam esse valor, nunca `ultimoId` ou a quantidade ativa.
  O cenário real tem `ultimoId=100001`, mas a população inicial segue 100000.
- Overflow divide o bucket, aumenta sua profundidade local e redistribui os pares;
  duplica o diretório apenas quando local = global. Aliases não envolvidos ficam intactos.
- Delete compacta os pares do bucket, **sem fusão nem redução do diretório**.
  A reconstrução recompõe apenas os ativos e pode produzir estrutura menor.
- Limites operacionais explícitos: N inicial entre 1 e 20 milhões, p até 24.
  Se um limite impedir a operação, o estado pendente exige reconstrução.

Formato binário explícito, big-endian, sem serialização de objetos:

| Arquivo | Formato |
| --- | --- |
| `data/index/hash_diretorio.idx` | Cabeçalho de 64 bytes: magic, versão, p, capacidade, N inicial, número de buckets, entradas, pendência, tamanho de bucket e UUID da geração; depois `2^p` ponteiros long |
| `data/index/hash_buckets.idx` | Cabeçalho de 24 bytes (magic, versão, mesma geração); páginas de `8 + 12 × capacidade` bytes: profundidade local int, quantidade int e pares int/long |

O diretório é consultado no disco. Apenas o bucket corrente é carregado em RAM.
A validação usa conjuntos auxiliares para detectar duplicatas, buckets órfãos,
aliases incorretos, contagens e posições inválidas. A geração impede misturar os
dois arquivos de reconstruções diferentes.

## Duas listas reais

A análise prévia dos primeiros 5000 registros encontrou 70 anos e 46
características distintas. Por exemplo: 2017 (502), 2018 (474), `gas` (4221),
`automatic` (3587). Os campos são repetidos e úteis para filtros não únicos.

- `data/index/lista_ano.idx`: chave **int ano**.
- `data/index/lista_caracteristicas.idx`: chave **String** para cada elemento.
- Normalização: `trim().toLowerCase(Locale.ROOT)`, ignorando vazios e repetidos
  dentro do mesmo carro. Os valores originais de Carro não são alterados.
- Cabeçalho de 40 bytes: magic, versão, tipo, pendência, quantidade de termos,
  quantidade de postings e ponteiro do primeiro termo.
- Cada termo tem próximo termo, cabeça, cauda e contagem (quatro longs),
  seguidos de chave int ou UTF. Cada posting ocupa 20 bytes: id int,
  posição long e próximo long. `-1` encerra uma cadeia.
- O dicionário auxiliar de termos é reconstruído do arquivo ao abrir; os postings
  permanecem no disco. São ordenados por ID, inclusive com inserção fora de ordem.
- Append crescente usa a cauda em O(1); inserção/alteração/remoção arbitrária
  percorre a lista do termo. Postings removidos e termos vazios ficam no arquivo;
  reconstruir compacta esse espaço. Não é uma implementação otimizada para carga
  massiva de IDs em ordem aleatória.
- Ano AND característica usa interseção com dois ponteiros, **O(n+m)**.
  Só depois os resultados são lidos por `readAtPosition(pos,id)`, sem Read
  sequencial nem varredura da base. A memória da busca cresce com os postings e
  resultados da consulta, não com toda a base.

## Manutenção e recuperação

`GerenciadorIndices` preserva o marcador comum `arvore_bplus.idx.pendente`,
gravado antes de alterar dados/índices. `IndicesSecundarios` concentra Hash e listas.

Create inclui os quatro índices. Update lê o carro antigo, atualiza as posições
e aplica diferenças de ano/termos, inclusive sem mudança de endereço. Delete
remove todas as referências depois de marcar a lápide. As consultas verificam a
assinatura dos dados e recusam posições com lápide ou ID diferente.

Se qualquer etapa falhar, **não há rollback dos dados**: todas as consultas
indexadas ficam bloqueadas até reconstruir. O marcador sobrevive à reabertura.
Reconstruções percorrem ativos de `dados.db`, um carro por vez, geram arquivos
temporários, validam, publicam todos e só então removem a pendência. Não usam CSV.
`fase2.meta` preserva a população inicial mesmo se um arquivo de índice faltar;
esse valor não é recalculado com base nos ativos. Não remover essa configuração
para tentar recalibrar a capacidade.

O CRUD sequencial do menu continua no fluxo existente: se houver índices ativos,
o serviço reconstrói todos após a operação. As duas ordenações externas usam a
mesma proteção e reconstrução. As APIs `criar/atualizar/excluir` já demonstram a
manutenção incremental conjunta; escolher o índice do CRUD no menu fica na Fase 3.

Uso local, **um escritor por vez**, sem suporte a processos concorrentes e sem
pretensão de transações ACID. Os cabeçalhos locais sinalizam operações incompletas;
o coordenador é a entrada indicada para alterações integradas. A assinatura usa
caminho, identidade, tamanho e data do arquivo; não substitui checksum contra
adulteração externa deliberada. Validações individuais conferem estrutura,
pertinência aos dados e cardinalidade/completude dos postings.

## Executar e testar

Na raiz do projeto, mantendo a compilação simples:

```sh
javac -encoding UTF-8 -d out -sourcepath src src/*.java
java -cp out TesteTP1
java -cp out TesteBPlus
java -cp out TesteHash
java -cp out TesteListas
java -cp out TesteFase2
java -Xmx512m -cp out TesteFase2 --base-real
java -Xmx512m -cp out TesteFase2 --reabrir
java -cp out Main
```

As duas últimas variantes de teste exigem a base oficial já carregada.
`--base-real` reconstrói os quatro índices; `--reabrir` apenas reabre, valida e
consulta em outro processo. Ambos conferem que dados/CSV não mudaram byte a byte.
Os testes pequenos trabalham em `build-test/`; execute TesteTP1 antes dos demais,
pois sua suíte limpa essa pasta. O TesteTP1 `--base-100k` recarrega e modifica a
base: executá-lo somente em diretório isolado com cópia de `data/base.csv` e
classpath absoluto para `out`. Foi executado dessa forma, seguido de
`TesteBPlus --base-real` na mesma cópia; ambos passaram.

Menu principal 9 → submenu de índices: 6 ativa/reconstrói todos (ordem B+ e
população inicial); 1 reconstrói os já ativos; 7–9 consultam/informam/validam Hash;
10–12 buscam ano, característica e combinação; 13–14 validam cada lista.
A primeira ativação oficial recebe **100000**. Repetições preservam o valor salvo.
Os cabeçalhos gerais do programa/README serão tratados na fase final.

Também passaram: 1200 operações Hash com oráculo, 800 operações por lista,
split com profundidades locais 1/2/3/4, duplicação, corrupção, reabertura,
CRUD dos quatro índices, lápides, mudança de atributos, duas ordenações,
falhas parciais e recuperação. As consultas de integração bloqueiam os métodos
de varredura. O menu foi exercitado em base temporária com ativação, consultas,
dois updates, delete e as quatro validações.

## Resultado real — 25/09/2026

100000 ativos; B+ ordem 32, altura 4; Hash p final 5, 32 buckets, locais 5,
5000 entradas por bucket. Lista Ano: 102 termos/100000 postings.
Lista Características: 47 termos/535292 postings. **2020 AND automatic = 2478**,
igual ao oráculo sequencial exclusivo do teste, inclusive após reabrir.

| Arquivo | Bytes | MiB |
| --- | ---: | ---: |
| B+ | 2609191 | 2,488318 |
| Hash diretório | 320 | 0,000305 |
| Hash buckets | 1920280 | 1,831322 |
| Lista Ano | 2003712 | 1,910889 |
| Lista Características | 10707775 | 10,211730 |

Medição simples após reabrir, mesma sequência `[1,2,50000,99999,100000,100001]`
repetida 3 vezes (18 consultas; último ID ausente): sequencial **4987639875 ns**,
B+ **1991584 ns**, Hash **1536209 ns**. Totais incluem obtenção do carro por seek
nas buscas indexadas; arquivos de índice abertos fora da região cronometrada.
Ordem de medição fixa e caches do sistema influenciam; não é benchmark científico
nem garantia de proporção de ganho em outras cargas.

Todos os arquivos gerados em `data/index/` (incluindo metadados, pendência e
temporários) e `dados.db` permanecem ignorados. Nenhum binário de índice versionado.

Pendências da Fase 3: seletor final de CRUD, validação global no menu,
README/documentação final e vídeo. **Estado: aguardando revisão humana.**
