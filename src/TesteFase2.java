import dao.ArquivoSequencial;
import index.bplus.ArvoreBPlus;
import index.hash.HashEstendido;
import index.lista.ListaInvertida;
import model.Carro;
import service.GerenciadorIndices;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Oráculo sequencial exclusivo dos testes. As consultas reais têm a varredura bloqueada. */
public class TesteFase2 {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--base-real") || args[0].equals("--reabrir"))) {
            baseReal(args[0].equals("--base-real")); return;
        }
        Files.createDirectories(Path.of("build-test"));
        Path pasta = Files.createTempDirectory(Path.of("build-test"), "fase2-");
        Path db = pasta.resolve("dados.db"), idx = pasta.resolve("index/arvore_bplus.idx");
        SemVarredura dados = new SemVarredura(db);
        dados.reinicializar(40);
        for (int id : new int[]{20, 3, 1, 8, 17, 6, 5, 7, 9, 2, 15, 4}) dados.appendComId(carro(id));
        dados.delete(4);
        GerenciadorIndices g = new GerenciadorIndices(dados, idx);
        falha(() -> g.reconstruirTodos(4, 0), "População inicial obrigatória");
        g.reconstruirTodos(4, 40);
        verificar(g, dados);
        exigir(g.buscarHash(4) == null && g.buscarHash(999) == null, "Lápides e ausentes");
        exigir(g.buscarAno(1800).isEmpty() && g.buscarCaracteristica("inexistente").isEmpty(), "Filtros ausentes");

        Carro novo = carro(0);
        exigir(g.criar(novo) == 41, "Create preserva ultimoId");
        verificar(g, dados);
        long anterior = posicao(idx, 41), tamanho = Files.size(db);
        novo.setAno(2021); novo.setCaracteristicas(List.of("abs", "automatic"));
        exigir(g.atualizar(novo), "Update mesmo tamanho");
        exigir(posicao(idx, 41) == anterior && Files.size(db) == tamanho, "Endereço preservado");
        exigir(!ids(g.buscarAno(2020)).contains(41) && ids(g.buscarAno(2021)).contains(41), "Troca de ano");
        exigir(!ids(g.buscarCaracteristica("gas")).contains(41) && ids(g.buscarCaracteristica("abs")).contains(41), "Troca de termo sem realocar");
        verificar(g, dados);
        novo.setCaracteristicas(List.of("gas", "automatic"));
        exigir(g.atualizar(novo), "Prepara cenário gas/automatic");
        novo.setNome("nome-ampliado-para-demonstrar-a-realocacao");
        novo.setCaracteristicas(List.of("gas", "manual", "sedan"));
        exigir(g.atualizar(novo), "Update com realocação");
        long nova = posicao(idx, 41);
        exigir(nova != anterior && lapide(db, anterior) == 1, "Nova posição e lápide antiga");
        falha(() -> dados.readAtPosition(anterior, 41), "Leitura da lápide antiga");
        exigir(!ids(g.buscarCaracteristica("automatic")).contains(41), "automatic removido");
        for (String termo : List.of("gas", "manual", "sedan")) exigir(ids(g.buscarCaracteristica(termo)).contains(41), "Termo mantido/adicionado");
        exigir(posHash(idx, 41) == nova, "Hash aponta nova posição");
        try (ListaInvertida a = ListaInvertida.abrir(idx.resolveSibling("lista_ano.idx"));
             ListaInvertida c = ListaInvertida.abrir(idx.resolveSibling("lista_caracteristicas.idx"))) {
            exigir(a.buscar(2021).stream().anyMatch(p -> p.id == 41 && p.posicao == nova), "Ano aponta nova posição");
            for (String termo : List.of("gas", "manual", "sedan")) {
                exigir(c.buscar(termo).stream().anyMatch(p -> p.id == 41 && p.posicao == nova), "Características apontam nova posição");
            }
        }
        verificar(g, dados);
        exigir(g.excluir(41) && g.buscar(41) == null && g.buscarHash(41) == null, "Delete B+/Hash");
        falha(() -> dados.readAtPosition(nova, 41), "Delete impede seek na lápide");
        exigir(!ids(g.buscarAno(2021)).contains(41), "Delete ano");
        for (String termo : List.of("gas", "manual", "sedan")) exigir(!ids(g.buscarCaracteristica(termo)).contains(41), "Delete características");
        exigir(!g.excluir(41) && !g.atualizar(carro(999)), "Mutações ausentes");
        Carro normalizado = carro(0);
        normalizado.setCaracteristicas(List.of(" GAS ", "gas", "AUTOMATIC", "automatic", "", "  "));
        g.criar(normalizado);
        exigir(g.buscarHash(normalizado.getId()).getCaracteristicas().equals(normalizado.getCaracteristicas()), "Não modifica strings originais");
        exigir(g.buscarCombinada(2020, "  AUTOMATIC ").stream().filter(c -> c.getId() == normalizado.getId()).count() == 1, "Um posting por termo/id");
        verificar(g, dados);
        System.out.println("FASE2 CRUD: quatro índices coerentes; mesmo tamanho, realocação, atributos e lápides: OK");

        long pos20 = posicao(idx, 20);
        g.ordenar(pasta.resolve("temp-comum"), 3, 3, false);
        exigir(posicao(idx, 20) != pos20 && dados.contarLapides() == 0, "Intercalação altera endereços e compacta");
        verificar(g, dados);
        Carro ampliado = g.buscar(3); ampliado.setNome("outro-nome-maior-para-nova-lapide"); g.atualizar(ampliado);
        long pos3 = posicao(idx, 3);
        g.ordenar(pasta.resolve("temp-selecao"), 3, 3, true);
        exigir(posicao(idx, 3) != pos3 && dados.contarLapides() == 0, "Seleção altera endereços e compacta");
        verificar(new GerenciadorIndices(dados, idx), dados);
        try (HashEstendido h = HashEstendido.abrir(idx.resolveSibling("hash_diretorio.idx"), idx.resolveSibling("hash_buckets.idx"))) {
            exigir(h.getCapacidade() == 2 && h.getQuantidadeInicial() == 40, "Capacidade inicial preservada após CRUD e ordenações");
        }
        falhasParciais(g, dados, idx);

        Path vazio = pasta.resolve("vazio/dados.db");
        GerenciadorIndices gv = new GerenciadorIndices(new ArquivoSequencial(vazio), pasta.resolve("vazio/index/arvore_bplus.idx"));
        gv.reconstruirTodos(4, 40);
        exigir(gv.buscarHash(1) == null && gv.buscarAno(2020).isEmpty(), "Reconstrução de base ativa vazia");
        gv.validar(); gv.validarHash(); gv.validarListaAno(); gv.validarListaCaracteristicas();
        System.out.println("TODOS OS TESTES INTEGRAÇÃO FASE 2 PASSARAM.");
    }

    private static void falhasParciais(GerenciadorIndices g, SemVarredura dados, Path idx) throws Exception {
        Path lista = idx.resolveSibling("lista_caracteristicas.idx");
        try (RandomAccessFile f = new RandomAccessFile(lista.toFile(), "rw")) { f.writeInt(0); }
        int esperado = dados.getUltimoId() + 1;
        falha(() -> g.criar(carro(0)), "Falha após dados/B+ alterados");
        exigir(new ArquivoSequencial(dados.getCaminho()).read(esperado) != null, "Dados não são revertidos");
        bloqueadas(new GerenciadorIndices(dados, idx));
        g.reconstruir(4); verificar(g, dados);

        try (ListaInvertida c = ListaInvertida.abrir(lista)) { c.remover("automatic", esperado); }
        Carro alterado = g.buscar(esperado); alterado.setAno(2022);
        falha(() -> g.atualizar(alterado), "Falha no update após alteração dos demais índices");
        bloqueadas(new GerenciadorIndices(dados, idx));
        g.reconstruir(4); verificar(g, dados);

        try (ListaInvertida a = ListaInvertida.abrir(idx.resolveSibling("lista_ano.idx"))) { a.remover(2022, esperado); }
        falha(() -> g.excluir(esperado), "Falha parcial do delete");
        bloqueadas(new GerenciadorIndices(dados, idx));
        g.reconstruir(4); verificar(g, dados);

        // Somente arquivos gerados neste cenário isolado; recuperação sem CSV.
        Files.delete(idx.resolveSibling("hash_buckets.idx"));
        bloqueadas(g); g.reconstruir(4); verificar(g, dados);
        dados.falharPercurso = true;
        falha(() -> g.reconstruir(4), "Reconstrução interrompida");
        dados.falharPercurso = false;
        bloqueadas(new GerenciadorIndices(dados, idx));
        g.reconstruir(4); verificar(g, dados);
        falha(() -> g.reconstruirTodos(4, 80), "Não recalcula população inicial");
        g.reconstruir(4); exigir(g.quantidadeInicial() == 40, "Mantém configuração após tentativa incompatível");
        System.out.println("FASE2: duas ordenações, reabertura, quatro falhas parciais e reconstrução sem CSV: OK");
    }

    private static void bloqueadas(GerenciadorIndices g) throws Exception {
        falha(() -> g.buscar(1), "B+ bloqueada"); falha(() -> g.buscarHash(1), "Hash bloqueado");
        falha(() -> g.buscarAno(2020), "Ano bloqueado"); falha(() -> g.buscarCaracteristica("gas"), "Características bloqueadas");
        falha(() -> g.buscarCombinada(2020, "gas"), "Interseção bloqueada");
    }

    private static void verificar(GerenciadorIndices g, SemVarredura dados) throws Exception {
        g.validar(); g.validarHash(); g.validarListaAno(); g.validarListaCaracteristicas();
        List<Carro> oraculo = new ArquivoSequencial(dados.getCaminho()).listarAtivos();
        Set<Integer> anos = new TreeSet<>(); Set<String> termos = new TreeSet<>();
        for (Carro c : oraculo) { anos.add(c.getAno()); termos.addAll(termos(c)); }
        dados.bloquear = true;
        try {
            for (Carro c : oraculo) exigir(c.equals(g.buscar(c.getId())) && c.equals(g.buscarHash(c.getId())), "Seek retorna conteúdo integral");
            for (int ano : anos) {
                comparar(g.buscarAno(ano), oraculo, ano, null);
                for (String termo : termos) comparar(g.buscarCombinada(ano, termo), oraculo, ano, termo);
            }
            for (String termo : termos) comparar(g.buscarCaracteristica(" " + termo.toUpperCase(java.util.Locale.ROOT) + " "), oraculo, null, termo);
        } finally { dados.bloquear = false; }
    }

    private static void comparar(List<Carro> obtido, List<Carro> todos, Integer ano, String termo) {
        List<Carro> esperado = todos.stream().filter(c -> (ano == null || c.getAno() == ano)
                && (termo == null || termos(c).contains(termo))).sorted(java.util.Comparator.comparingInt(Carro::getId)).collect(Collectors.toList());
        exigir(obtido.equals(esperado), "Conteúdo/ordem de consulta equivalente ao oráculo");
    }

    private static void baseReal(boolean reconstruir) throws Exception {
        Path db = Path.of("data/dados.db"), csv = Path.of("data/base.csv"), idx = Path.of("data/index/arvore_bplus.idx");
        exigir(Files.isRegularFile(db), "Carregue a base oficial antes; este teste não recarrega o CSV");
        byte[] antes = digest(db), csvAntes = digest(csv);
        long inicial;
        try (Stream<String> linhas = Files.lines(csv)) { inicial = linhas.skip(1).count(); }
        exigir(inicial == 100000, "Quantidade inicial oficial");
        SemVarredura dados = new SemVarredura(db);
        GerenciadorIndices g = new GerenciadorIndices(dados, idx);
        long inicio = System.nanoTime();
        if (reconstruir) g.reconstruirTodos(32, inicial);
        System.out.println("FASE2_REAL " + (reconstruir ? "reconstrucao_ns=" : "reabertura_ns=") + (System.nanoTime() - inicio));
        System.out.println(g.validar()); System.out.println(g.validarHash());
        System.out.println(g.validarListaAno()); System.out.println(g.validarListaCaracteristicas());
        try (HashEstendido h = HashEstendido.abrir(idx.resolveSibling("hash_diretorio.idx"), idx.resolveSibling("hash_buckets.idx"))) {
            exigir(h.getQuantidadeInicial() == 100000 && h.getCapacidade() == 5000 && h.getQuantidade() == 100000, "Hash real com população/capacidade/entradas oficiais");
        }
        List<Carro> oraculo = new ArquivoSequencial(db).listarAtivos(); // Só no teste.
        exigir(oraculo.size() == 100000, "Base ativa esperada de 100K");
        int[] consultas = {1, 2, 50000, 99999, 100000, 100001};
        GerenciadorIndices reaberto = new GerenciadorIndices(dados, idx);
        dados.bloquear = true;
        try {
            for (int id : consultas) {
                Carro esperado = oraculo.stream().filter(c -> c.getId() == id).findFirst().orElse(null);
                exigir(java.util.Objects.equals(esperado, reaberto.buscar(id)), "Amostra B+ " + id);
                exigir(java.util.Objects.equals(esperado, reaberto.buscarHash(id)), "Amostra Hash " + id);
            }
            comparar(reaberto.buscarAno(2020), oraculo, 2020, null);
            comparar(reaberto.buscarCaracteristica("  AUTOMATIC "), oraculo, null, "automatic");
            List<Carro> resultado = reaberto.buscarCombinada(2020, "automatic");
            comparar(resultado, oraculo, 2020, "automatic");
            exigir(!resultado.isEmpty(), "Combinação real não vazia");
            exigir(reaberto.buscarAno(1800).isEmpty() && reaberto.buscarCaracteristica("termo-inexistente-215").isEmpty(), "Ausentes reais");
            System.out.println("BUSCA_COMBINADA ano=2020; caracteristica=automatic; resultados=" + resultado.size() + "; oráculo/seek=OK");
        } finally { dados.bloquear = false; }
        System.out.println("BASE_REAL ativos=" + oraculo.size() + "; ultimoId=" + dados.getUltimoId());
        System.out.println(g.informacoesHash());
        System.out.println(new GerenciadorIndices(new ArquivoSequencial(db), idx).compararBuscas(consultas, 3));
        for (String nome : List.of("arvore_bplus.idx", "hash_diretorio.idx", "hash_buckets.idx", "lista_ano.idx", "lista_caracteristicas.idx")) {
            long bytes = Files.size(idx.resolveSibling(nome));
            System.out.printf(java.util.Locale.ROOT, "%s=%d bytes (%.6f MiB)%n", nome, bytes, bytes / 1048576.0);
        }
        exigir(Arrays.equals(antes, digest(db)) && Arrays.equals(csvAntes, digest(csv)), "Dados/CSV não foram alterados pelo teste");
        System.out.println("BASE REAL FASE 2: OK; dados e CSV preservados byte a byte.");
    }

    private static byte[] digest(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) d.update(buffer, 0, n);
        }
        return d.digest();
    }
    private static Set<String> termos(Carro c) {
        return c.getCaracteristicas().stream().map(s -> s.trim().toLowerCase(java.util.Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
    private static List<Integer> ids(List<Carro> carros) { return carros.stream().map(Carro::getId).collect(Collectors.toList()); }
    private static Carro carro(int id) {
        return new Carro(id, id > 0 ? Carro.codigoPorId(id) : "", "carro-" + id, LocalDate.of(2026, 9, 25), List.of("gas", "automatic"), 2020);
    }
    private static long posicao(Path idx, int id) throws IOException { try (ArvoreBPlus a = ArvoreBPlus.abrir(idx)) { return a.buscar(id); } }
    private static long posHash(Path idx, int id) throws IOException {
        try (HashEstendido h = HashEstendido.abrir(idx.resolveSibling("hash_diretorio.idx"), idx.resolveSibling("hash_buckets.idx"))) { return h.buscar(id); }
    }
    private static byte lapide(Path db, long pos) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(db.toFile(), "r")) { f.seek(pos); return f.readByte(); }
    }
    private static void exigir(boolean condicao, String mensagem) { if (!condicao) throw new AssertionError(mensagem); }
    private static void falha(Acao acao, String mensagem) throws Exception {
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { return; }
        throw new AssertionError("Deveria recusar: " + mensagem);
    }
    @FunctionalInterface private interface Acao { void executar() throws Exception; }
    private static class SemVarredura extends ArquivoSequencial {
        boolean bloquear, falharPercurso;
        SemVarredura(Path db) { super(db); }
        @Override public Carro read(int id) { throw new AssertionError("Read sequencial indevido"); }
        @Override public List<Carro> listarAtivos() throws IOException {
            if (bloquear) throw new AssertionError("Varredura indevida em consulta indexada");
            return super.listarAtivos();
        }
        @Override public void percorrerAtivos(VisitanteRegistro visitante) throws IOException {
            if (bloquear) throw new AssertionError("Varredura indevida em consulta indexada");
            if (falharPercurso) throw new IOException("Interrupção controlada no cenário isolado");
            super.percorrerAtivos(visitante);
        }
    }
}
