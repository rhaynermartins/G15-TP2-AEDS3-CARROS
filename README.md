# TP2 AEDS III — Carros

## Integrantes

- Gabriel Benicio Fonseca
- Rhayner Martins

## Base de dados

100.000 registros reais do [Used Cars Dataset — Craigslist](https://www.kaggle.com/datasets/austinreese/craigslist-carstrucks-data).
O recorte versionado é `data/base.csv`. Nome reúne fabricante/modelo;
`dataRegistro` vem de `posting_date`; características são separadas por `|`.
O arquivo binário mantém `ultimoId`, lápide, tamanho e dados de cada carro.

## Estruturas implementadas

- **Árvore B+:** escolhida pelo acesso por ID, páginas em disco e folhas ordenadas.
- **Hashing Estendido:** chave ID, `h(k) = k mod 2^p`, profundidade inicial 1.
  Cada bucket guarda 5% da população inicial: 100.000 × 0,05 = **5.000 pares ID/posição**.
  Essa configuração persiste e não muda após Create/Delete.
- **Lista Invertida por ano:** chave inteira e postings ordenados por ID.
- **Lista Invertida por características:** cada termo é normalizado com trim/minúsculas,
  sem alterar o carro original. A consulta ano AND característica usa interseção O(n+m).

## CRUD Indexado

Compile com `javac -encoding UTF-8 -d out -sourcepath src src/*.java` e execute
`java -cp out Main`. No Windows, use `compilar.bat` e `executar.bat`.

O menu TP1 foi preservado. Entre na opção **9 — TP2**.
Na primeira execução, carregue o CSV pelo menu principal e construa os índices
na opção **6** do TP2: ordem B+ sugerida 32 e população inicial oficial 100000.

Read, Update e Delete indicam B+, Hash ou Lista. As listas mostram 20 resultados
por página; Update/Delete aceitam somente IDs da pesquisa. A leitura usa seek,
não Read sequencial. Create usa ultimoId + 1, sem buscar nem reutilizar IDs.

Toda mutação mantém os quatro índices. Update pode preservar ou mudar a posição.
Carga e ambas as ordenações reconstroem os índices ativos. Falhas parciais bloqueiam
consultas até reconstrução; não há rollback dos dados. Use uma aplicação/escritor
por vez. A opção **7** valida dados e índices; **8** mostra informações; **9** compara buscas.
Seleções anteriores a uma mutação precisam ser pesquisadas novamente.

## Arquivos de índices

Em `data/index/`: `arvore_bplus.idx`, `hash_diretorio.idx`, `hash_buckets.idx`,
`lista_ano.idx` e `lista_caracteristicas.idx`. Metadados guardam configuração e
consistência. São gerados a partir de `dados.db`, sem recarregar CSV.
Índices, dados binários e temporários não são versionados.

## Testes

Após compilar, execute `java -cp out NOME` para:
`TesteTP1`, `TesteBPlus`, `TesteHash`, `TesteListas`, `TesteFase2` e `TesteTP2`,
nessa ordem. No Windows, `testar.bat` executa as seis suítes.

Com a base oficial e os índices já configurados:
`java -Xmx512m -cp out TesteTP2 --base-real` reconstrói e valida;
`java -Xmx512m -cp out TesteTP2 --reabrir` testa outro processo sem reconstruir.
O teste TP1 `--base-100k` recarrega a base: use apenas uma cópia isolada.

## Resultados principais

100.000 ativos e entradas em B+/Hash. Hash: p=5, 32 buckets, capacidade 5.000.
Ano: 102 termos/100.000 postings. Características: 47 termos/535.292 postings.
**2020 AND automatic: 2.478 carros**, igual ao oráculo sequencial dos testes.
CRUD pelas três estruturas, duas ordenações, recuperação e reabertura: OK.

Medições e tamanhos em [RESULTADO_TESTES.txt](RESULTADO_TESTES.txt).
Formatos e decisões em [Fase 1](docs/TP2_FASE1.md) e [Fase 2](docs/TP2_FASE2.md).
O vídeo existente é do TP1 e foi preservado; o vídeo do TP2 permanece pendente.
