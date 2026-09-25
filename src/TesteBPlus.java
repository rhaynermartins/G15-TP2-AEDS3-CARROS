import dao.ArquivoSequencial;
import index.bplus.ArvoreBPlus;
import model.Carro;
import service.GerenciadorIndices;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/** Testes determinísticos sem bibliotecas externas. TreeMap é apenas o oráculo dos testes. */
public class TesteBPlus {
    private static Path raiz;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--base-real")) {
            baseReal();
            return;
        }
        Files.createDirectories(Path.of("build-test"));
        raiz = Files.createTempDirectory(Path.of("build-test"), "bplus-");
        estrutura();
        intercaladas();
        integracao();
        corrupcao();
        System.out.println("TODOS OS TESTES B+ PASSARAM.");
    }

    private static void estrutura() throws Exception {
        for (int ordem = 3; ordem <= 9; ordem++) {
            Path indice = raiz.resolve("ordem-" + ordem + ".idx");
            List<Integer> ids = new ArrayList<>();
            for (int i = 1; i <= 180; i++) ids.add(i);
            Collections.shuffle(ids, new Random(100 + ordem));
            try (ArvoreBPlus arvore = ArvoreBPlus.criar(indice, ordem)) {
                exigir(arvore.buscar(10) == -1, "Busca na árvore vazia");
                arvore.validar(null);
                for (int id : ids) {
                    arvore.inserir(id, id * 100L);
                    arvore.validar(null);
                }
                exigir(arvore.getSplitsFolha() > 0 && arvore.getSplitsInterno() > 0,
                        "Força splits de folhas/internos e crescimento da raiz");
                falha(() -> arvore.inserir(1, 100), "ID duplicado");
                exigir(arvore.getQuantidade() == 180, "Duplicado não altera contagem");
                exigir(arvore.buscar(1) == 100 && arvore.buscar(90) == 9000
                        && arvore.buscar(180) == 18000 && arvore.buscar(181) == -1, "Busca extremos/meio/ausente");
                exigir(arvore.atualizarPosicao(90, 999999), "Atualização de posição");
                exigir(!arvore.atualizarPosicao(181, 999999), "Update ausente");
            }
            try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
                exigir(arvore.getOrdem() == ordem, "Ordem recuperada do cabeçalho");
                exigir(arvore.buscar(90) == 999999, "Posição persiste na reabertura");
                for (int id : ids) exigir(arvore.buscar(id) == (id == 90 ? 999999 : id * 100L), "Persistência");
                List<Integer> folhas = new ArrayList<>();
                arvore.percorrerFolhas((id, pos) -> folhas.add(id));
                for (int i = 0; i < folhas.size(); i++) exigir(folhas.get(i) == i + 1, "Folhas em ordem");
                Collections.shuffle(ids, new Random(200 + ordem));
                for (int id : ids) {
                    exigir(arvore.remover(id), "Remoção existente");
                    exigir(arvore.buscar(id) == -1, "Removido não encontrado");
                    arvore.validar(null);
                }
                exigir(arvore.getRedistribuicoes() > 0, "Força redistribuição");
                exigir(arvore.getFusoes() > 0, "Força fusão");
                exigir(arvore.getQuantidade() == 0, "Árvore vazia após remover tudo");
                exigir(!arvore.remover(7), "Remoção ausente");
                long tamanho = Files.size(indice);
                for (int id = 1; id <= 30; id++) arvore.inserir(id, id * 200L);
                exigir(Files.size(indice) == tamanho, "Páginas livres reaproveitadas");
                System.out.println("ORDEM " + ordem + ": splits/persistência/redistribuição/fusão/raiz OK; "
                        + arvore.validar(null));
            }
        }
        falha(() -> ArvoreBPlus.criar(raiz.resolve("invalido.idx"), 2), "Ordem mínima");
        falha(() -> ArvoreBPlus.criar(raiz.resolve("enorme.idx"), Integer.MAX_VALUE), "Ordem excessiva");
        falha(() -> ArvoreBPlus.abrir(raiz.resolve("ausente.idx")), "Arquivo ausente");
    }

    private static void intercaladas() throws Exception {
        Path indice = raiz.resolve("aleatorio.idx");
        TreeMap<Integer, Long> esperado = new TreeMap<>();
        Random random = new Random(159);
        try (ArvoreBPlus arvore = ArvoreBPlus.criar(indice, 5)) {
            for (int rodada = 0; rodada < 1200; rodada++) {
                int id = 1 + random.nextInt(160);
                int op = random.nextInt(3);
                if (op == 0 && !esperado.containsKey(id)) {
                    long pos = 4L + rodada * 27;
                    arvore.inserir(id, pos);
                    esperado.put(id, pos);
                } else if (op == 1) {
                    boolean existe = esperado.remove(id) != null;
                    exigir(arvore.remover(id) == existe, "Remoções intercaladas");
                } else if (op == 2) {
                    long pos = 4L + rodada * 37;
                    exigir(arvore.atualizarPosicao(id, pos) == esperado.containsKey(id), "Updates intercalados");
                    if (esperado.containsKey(id)) esperado.put(id, pos);
                }
                arvore.validar(null);
                TreeMap<Integer, Long> obtido = new TreeMap<>();
                arvore.percorrerFolhas(obtido::put);
                exigir(obtido.equals(esperado), "Oráculo independente após cada operação");
            }
        }
        System.out.println("1200 operações intercaladas comparadas com oráculo: OK");
    }

    private static void integracao() throws Exception {
        Path pasta = Files.createDirectory(raiz.resolve("integracao"));
        Path db = pasta.resolve("dados.db");
        Path idx = pasta.resolve("arvore.idx");
        // Impede que uma falsa leitura indexada use qualquer Read sequencial.
        ArquivoSequencial dados = new ArquivoSequencial(db) {
            @Override public Carro read(int id) { throw new AssertionError("Read sequencial indevido"); }
        };
        dados.reinicializar(40);
        int[] ids = {20, 3, 17, 1, 15, 2, 14, 4, 13, 5, 12, 6, 11, 7, 10, 8, 9, 16};
        for (int id : ids) dados.appendComId(carro(id, "carro-" + id));
        dados.delete(4);
        GerenciadorIndices g = new GerenciadorIndices(dados, idx);
        g.reconstruir(4);
        g.validar();
        exigir(g.buscar(4) == null && g.buscar(777) == null, "Ausente/apagado");
        for (int id : ids) if (id != 4) exigir(g.buscar(id).getId() == id, "Busca direta");

        Carro novo = carro(0, "novo");
        exigir(g.criar(novo) == 41, "Create preserva ultimoId");
        long pos = posicao(idx, 41);
        long tamanho = Files.size(db);
        novo.setAno(2027);
        exigir(g.atualizar(novo) && posicao(idx, 41) == pos && Files.size(db) == tamanho,
                "Update mesmo tamanho mantém posição");
        novo.setNome("nome-ampliado-para-forcar-realocacao");
        exigir(g.atualizar(novo) && posicao(idx, 41) != pos, "Update diferente muda posição");
        exigir(lapide(db, pos) == 1 && g.buscar(41).getNome().equals(novo.getNome()), "Versão antiga com lápide");
        long novaPos = posicao(idx, 41);
        exigir(g.excluir(41) && g.buscar(41) == null && lapide(db, novaPos) == 1, "Delete integrado");
        exigir(!g.excluir(41) && !g.atualizar(carro(999, "ausente")), "Mutações ausentes");
        g.validar();

        long pos20 = posicao(idx, 20);
        g.ordenar(pasta.resolve("temp-comum"), 3, 3, false);
        exigir(posicao(idx, 20) != pos20 && dados.contarLapides() == 0, "Ordenação muda endereços e compacta");
        exigir(dados.getUltimoId() == 41, "Ordenação preserva cabeçalho");
        g.validar();
        Carro c = g.buscar(3);
        c.setNome(c.getNome() + "-maior");
        g.atualizar(c);
        g.ordenar(pasta.resolve("temp-selecao"), 3, 3, true);
        g.validar();
        exigir(g.criar(carro(0, "pos-ordenacao")) == 42, "CRUD após ordenação");
        exigir(g.buscar(42) != null && g.excluir(42), "Read/delete após ordenação");
        g.validar();

        Files.delete(idx); // Somente o índice deste cenário temporário; dados preservados.
        falha(() -> g.buscar(1), "Índice ausente tem mensagem controlada");
        g.reconstruir(5);
        g.validar();
        exigir(new GerenciadorIndices(dados, idx).buscar(1).getId() == 1, "Reabertura do serviço");

        // Mutação feita fora do serviço: mesmo tamanho, sem crescimento do arquivo.
        Carro alterado = g.buscar(2);
        alterado.setAno(1999);
        dados.updateAtPosition(posicao(idx, 2), alterado);
        falha(() -> g.buscar(2), "Assinatura detecta alteração externa");
        g.reconstruir(5);
        exigir(g.buscar(2).getAno() == 1999, "Reconstrução após alteração externa");

        long pos1 = posicao(idx, 1);
        long pos2 = posicao(idx, 2);
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(idx)) { arvore.atualizarPosicao(1, pos2); }
        falha(() -> g.buscar(1), "Recusa ID diferente no endereço");
        falha(g::validar, "Validação detecta ID/posição incorretos");
        g.reconstruir(5);
        dados.deleteAtPosition(pos1, 1);
        falha(() -> dados.readAtPosition(pos1, 1), "Leitura direta recusa lápide");
        g.reconstruir(5);
        exigir(g.buscar(1) == null, "Reconstrução ignora versão excluída");

        falha(() -> g.executarAlteracaoSequencial(() -> {
            dados.create(carro(0, "gravado-antes-da-falha"));
            throw new IOException("Falha simulada após gravar dados");
        }), "Falha deixa marcador persistente");
        GerenciadorIndices reaberto = new GerenciadorIndices(dados, idx);
        falha(() -> reaberto.buscar(3), "Reinício bloqueia índice pendente");
        reaberto.reconstruir(5);
        reaberto.validar();
        exigir(reaberto.buscar(dados.getUltimoId()) != null, "Recuperação inclui registro gravado");
        reaberto.executarAlteracaoSequencial(() -> dados.create(carro(0, "via-sequencial")));
        reaberto.validar();

        Path dbVazio = pasta.resolve("vazio.db");
        ArquivoSequencial vazio = new ArquivoSequencial(dbVazio);
        vazio.reinicializar(200);
        GerenciadorIndices gv = new GerenciadorIndices(vazio, pasta.resolve("vazio.idx"));
        gv.reconstruir(3);
        gv.ordenar(pasta.resolve("temp-vazio"), 2, 2, false);
        gv.validar();
        exigir(gv.criar(carro(0, "primeiro")) == 201, "Base vazia mantém ultimoId");
        gv.excluir(201);
        gv.validar();
        falha(() -> vazio.readAtPosition(-1, 1), "Offset negativo");
        falha(() -> vazio.readAtPosition(Files.size(dbVazio) + 1, 1), "Offset além do arquivo");
        System.out.println("CRUD direto, dois updates, delete, reconstrução, duas ordenações e recuperação: OK");
    }

    private static void corrupcao() throws Exception {
        Path original = raiz.resolve("saudavel.idx");
        try (ArvoreBPlus arvore = ArvoreBPlus.criar(original, 4)) {
            for (int id = 1; id <= 100; id++) arvore.inserir(id, 100L * id);
            arvore.validar(null);
        }
        corromper(original, "ordem", f -> { f.seek(8); f.writeInt(1); });
        corromper(original, "raiz", f -> { f.seek(16); f.writeLong(65); });
        corromper(original, "pendente", f -> { f.seek(56); f.writeLong(1); });
        corromper(original, "truncado", f -> f.setLength(f.length() - 1));
        corromper(original, "contagem", f -> { f.seek(40); f.writeLong(1); });
        corromper(original, "ciclo-folhas", f -> {
            f.seek(24); long primeira = f.readLong();
            f.seek(primeira + 5); f.writeLong(primeira);
        });
        corromper(original, "duplicado", f -> {
            f.seek(24); long primeira = f.readLong();
            f.seek(primeira + 13); int chave = f.readInt();
            f.writeInt(chave);
        });
        corromper(original, "separador", f -> {
            f.seek(16); long root = f.readLong();
            f.seek(root + 13); f.writeInt(1);
        });
        corromper(original, "ocupacao", f -> {
            f.seek(24); long primeira = f.readLong();
            f.seek(primeira + 1); f.writeInt(0);
        });
        corromper(original, "profundidade", f -> {
            f.seek(16); long root = f.readLong();
            f.seek(24); long primeira = f.readLong();
            f.seek(root + 13 + 4 * 3); f.writeLong(primeira);
        });
        System.out.println("10 cenários de corrupção recusados: OK");
    }

    private static void corromper(Path fonte, String nome, Corrupcao operacao) throws Exception {
        Path alvo = raiz.resolve("corrompido-" + nome + ".idx");
        Files.copy(fonte, alvo);
        try (RandomAccessFile f = new RandomAccessFile(alvo.toFile(), "rw")) { operacao.executar(f); }
        falha(() -> {
            try (ArvoreBPlus arvore = ArvoreBPlus.abrir(alvo)) { arvore.validar(null); }
        }, nome);
    }

    private static void baseReal() throws Exception {
        Path db = Path.of("data", "dados.db");
        if (!Files.isRegularFile(db)) throw new IOException("Carregue a base antes deste teste; CSV não será recarregado.");
        ArquivoSequencial dados = new ArquivoSequencial(db);
        List<Integer> ids = new ArrayList<>();
        dados.percorrerAtivos((id, pos) -> ids.add(id));
        exigir(!ids.isEmpty(), "Base real deve conter registros ativos");
        Collections.sort(ids);
        Path indice = Path.of("data", "index", "arvore_bplus.idx");
        GerenciadorIndices g = new GerenciadorIndices(dados, indice);
        long inicio = System.nanoTime();
        g.reconstruir(32);
        long tempo = System.nanoTime() - inicio;
        System.out.println(g.validar());
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            exigir(arvore.getQuantidade() == ids.size(), "Quantidade da B+ igual à base ativa");
        }
        int max = ids.get(ids.size() - 1);
        int ausente = max < Integer.MAX_VALUE ? max + 1 : 0;
        int[] amostras = {ids.get(0), ids.get(Math.min(1, ids.size() - 1)),
                ids.get((ids.size() - 1) / 2), ids.get(Math.max(0, ids.size() - 2)), max, ausente};
        GerenciadorIndices reaberto = new GerenciadorIndices(dados, indice);
        for (int id : amostras) {
            Carro a = dados.read(id), b = reaberto.buscar(id);
            exigir(a == null ? b == null : a.equals(b), "Amostra real " + id);
        }
        System.out.println("BASE_REAL entradas=" + ids.size() + "; ultimoId=" + dados.getUltimoId());
        System.out.println("amostras=" + Arrays.toString(amostras));
        System.out.println("construcao_ns=" + tempo + "; tamanho_idx_bytes=" + Files.size(indice));
        System.out.println(reaberto.informacoes());
        System.out.println(reaberto.compararBuscas(amostras, 3));
        System.out.println("BASE REAL B+: OK");
    }

    private static long posicao(Path idx, int id) throws IOException {
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(idx)) { return arvore.buscar(id); }
    }

    private static byte lapide(Path db, long pos) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(db.toFile(), "r")) { f.seek(pos); return f.readByte(); }
    }

    private static Carro carro(int id, String nome) {
        return new Carro(id, id > 0 ? Carro.codigoPorId(id) : "", nome,
                LocalDate.of(2026, 9, 25), Arrays.asList("gas", "automatic"), 2020);
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError(mensagem);
    }

    private static void falha(Acao acao, String mensagem) throws Exception {
        try { acao.executar(); }
        catch (IOException | IllegalArgumentException esperado) { return; }
        throw new AssertionError("Deveria recusar: " + mensagem);
    }

    @FunctionalInterface private interface Acao { void executar() throws Exception; }
    @FunctionalInterface private interface Corrupcao { void executar(RandomAccessFile f) throws Exception; }
}
