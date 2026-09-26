import dao.ArquivoSequencial;
import index.hash.HashEstendido;
import index.lista.ListaInvertida;
import model.Carro;
import service.CrudIndexado;
import service.GerenciadorIndices;
import service.Importador;

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

/** Integração final; algoritmos e cenários extensivos permanecem nas suítes anteriores. */
public class TesteTP2 {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--base-real") || args[0].equals("--reabrir"))) {
            baseReal(args[0].equals("--base-real")); return;
        }
        Files.createDirectories(Path.of("build-test"));
        Path pasta = Files.createTempDirectory(Path.of("build-test"), "tp2-final-");
        Path db = pasta.resolve("dados.db"), idx = pasta.resolve("index/arvore_bplus.idx");
        DadosVigiados dados = new DadosVigiados(db);
        dados.reinicializar(42);
        for (int id = 42; id >= 1; id--) {
            Carro c = carro(id);
            if (id == 42) c.setCaracteristicas(List.of("manual"));
            dados.appendComId(c);
        }
        GerenciadorIndices g = new GerenciadorIndices(dados, idx);
        g.reconstruirTodos(4, 100);
        CrudIndexado crud = new CrudIndexado(g);
        global(g);
        dados.bloquear = true;
        int leituras = dados.leituras;
        CrudIndexado.Consulta consulta = crud.consultar(2020, " AUTOMATIC ");
        exigir(consulta.total() == 41 && dados.leituras == leituras, "Consulta carrega postings, não carros");
        exigir(consulta.pagina(0).size() == 20 && dados.leituras == leituras + 20, "Página lê somente 20 carros");
        exigir(consulta.pagina(20).size() == 20 && consulta.pagina(40).size() == 1, "Paginação final");
        exigir(ids(consulta).get(0) == 1 && ids(consulta).get(40) == 41, "Resultados ordenados por ID");
        falha(() -> consulta.selecionar(42), "ID ativo fora do conjunto");
        falha(() -> consulta.selecionar(999), "ID inexistente fora do conjunto");
        exigir(crud.consultar(1800, null).total() == 0, "Ano inexistente");
        exigir(crud.consultar(null, "gas").total() == 41, "Read por característica");
        exigir(crud.consultar(2020, null).total() == 42, "Read por ano");
        dados.bloquear = false;

        for (CrudIndexado.Metodo metodo : CrudIndexado.Metodo.values()) {
            ArquivoSequencial.InfoRegistro criado = crud.criar(carro(0));
            global(g);
            CrudIndexado.RegistroLocalizado r = selecionar(crud, metodo, criado.id, 2020, "automatic");
            exigir(r.getMetodo() == metodo && r.getPosicao() == criado.posicao, "Origem e posição da seleção");
            Carro novo = r.getCarro(); novo.setAno(2021); novo.setCaracteristicas(List.of("abs", "automatic"));
            dados.bloquear = true;
            long mesma = crud.atualizar(r, novo);
            exigir(mesma == criado.posicao, "Update mesmo tamanho por " + metodo);
            dados.bloquear = false;
            global(g);
            CrudIndexado.RegistroLocalizado desatualizado = r;
            falha(() -> crud.excluir(desatualizado), "Seleção antiga após mutação");
            r = selecionar(crud, metodo, criado.id, 2021, "automatic");
            novo = r.getCarro(); novo.setNome("nome-ampliado-para-realocar-a-versao"); novo.setCaracteristicas(List.of("gas", "manual", "sedan"));
            dados.bloquear = true;
            long nova = crud.atualizar(r, novo);
            exigir(nova != mesma, "Update com realocação por " + metodo);
            dados.bloquear = false;
            falha(() -> dados.readAtPosition(mesma, criado.id), "Lápide antiga");
            global(g);
            CrudIndexado.RegistroLocalizado apagar = selecionar(crud, metodo, criado.id, 2021, "manual");
            dados.bloquear = true; crud.excluir(apagar); dados.bloquear = false;
            exigir(crud.localizar(CrudIndexado.Metodo.BPLUS, criado.id) == null
                    && crud.localizar(CrudIndexado.Metodo.HASH, criado.id) == null, "Delete direto por " + metodo);
            exigir(crud.consultar(2021, null).total() == 0 && crud.consultar(null, "sedan").total() == 0, "Delete remove postings");
            global(g);
            System.out.println("CRUD localizado via " + metodo + ": OK (mesmo tamanho, realocação e delete)");
        }
        exigir(crud.criar(carro(0)).id == 46, "Create não reutiliza IDs apagados");
        falha(() -> consulta.pagina(0), "Página de consulta anterior a mutações");
        CrudIndexado.RegistroLocalizado r = crud.localizar(CrudIndexado.Metodo.HASH, 1);
        falha(() -> new CrudIndexado(g).excluir(r), "Seleção de outro serviço");
        Carro outroId = r.getCarro(); outroId.setId(42);
        falha(() -> crud.atualizar(r, outroId), "ID da seleção imutável");
        for (boolean selecao : new boolean[]{false, true}) {
            Carro c = crud.localizar(CrudIndexado.Metodo.HASH, 2).getCarro(); c.setNome(c.getNome() + "-maior");
            crud.atualizar(crud.localizar(CrudIndexado.Metodo.HASH, 2), c);
            g.ordenar(pasta.resolve(selecao ? "selecao" : "balanceada"), 3, 5, selecao);
            global(g);
        }
        g.reconstruirTodos();
        exigir(g.ordem() == 4 && g.quantidadeInicial() == 100, "Reconstrução preserva configurações");
        global(new GerenciadorIndices(new ArquivoSequencial(db), idx));
        falhas(g, crud, dados, idx);
        recarga(g, pasta, db);
        System.out.println("TP2 FINAL: paginação, pertencimento, seleções antigas, ordenações, recarga e recuperação: OK");
        System.out.println("TODOS OS TESTES TP2 PASSARAM.");
    }

    private static CrudIndexado.RegistroLocalizado selecionar(CrudIndexado c, CrudIndexado.Metodo m, int id, int ano, String termo) throws IOException {
        return m == CrudIndexado.Metodo.LISTA ? c.consultar(ano, termo).selecionar(id) : c.localizar(m, id);
    }

    private static void falhas(GerenciadorIndices g, CrudIndexado crud, DadosVigiados dados, Path idx) throws Exception {
        Path lista = idx.resolveSibling("lista_ano.idx");
        try (ListaInvertida a = ListaInvertida.abrir(lista)) { a.remover(2020, 1); }
        byte[] antes = digest(lista);
        String resultado = g.validarTodos();
        exigir(resultado.contains("Lista Ano: ERRO") && !resultado.contains("Validação global: OK"), "Validação identifica divergência");
        exigir(Arrays.equals(antes, digest(lista)), "Validação não reconstrói silenciosamente");
        falha(() -> crud.localizar(CrudIndexado.Metodo.HASH, 1), "Diagnóstico bloqueia buscas até reconstruir");
        g.reconstruirTodos(); global(g);
        try (RandomAccessFile f = new RandomAccessFile(lista.toFile(), "rw")) { f.writeInt(0); }
        falha(() -> crud.criar(carro(0)), "Create parcialmente aplicado");
        GerenciadorIndices reaberto = new GerenciadorIndices(dados, idx);
        exigir(reaberto.validarTodos().contains("Índices inconsistentes"), "Pendência persistente");
        reaberto.reconstruirTodos(); global(reaberto);
        // Truncamento apenas de uma cópia isolada: o diagnóstico não recria o arquivo.
        Path copia = Files.createTempFile(idx.getParent(), "dados-invalidos-", ".db");
        Files.copy(dados.getCaminho(), copia, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile f = new RandomAccessFile(copia.toFile(), "rw")) { f.setLength(2); }
        GerenciadorIndices invalido = new GerenciadorIndices(new ArquivoSequencial(copia), copia.resolveSibling("invalido/arvore.idx"));
        exigir(invalido.validarTodos().contains("Arquivo de dados: ERRO") && Files.size(copia) == 2, "Cabeçalho inválido sem reparo silencioso");
    }

    private static void recarga(GerenciadorIndices g, Path pasta, Path db) throws Exception {
        Path csv = pasta.resolve("recarga.csv");
        Files.writeString(csv, "nome;caracteristicas;ano;dataRegistro\nrecarga;gas|automatic;2020;2026-09-26\n");
        g.executarAlteracaoSequencial(() -> new Importador().carregarBase(csv, db));
        global(g);
        exigir(g.buscarHash(1) != null && g.buscarHash(2) == null && g.quantidadeInicial() == 100, "Recarga substitui índices sem recalibrar Hash");
    }

    private static void baseReal(boolean reconstruir) throws Exception {
        Path db = Path.of("data/dados.db"), idx = Path.of("data/index/arvore_bplus.idx");
        byte[] antes = digest(db);
        DadosVigiados dados = new DadosVigiados(db);
        GerenciadorIndices g = new GerenciadorIndices(dados, idx);
        if (reconstruir) g.reconstruirTodos();
        String validacao = global(g);
        exigir(validacao.contains("registros ativos=100000") && validacao.contains("B+: OK; entradas=100000"), "Base oficial completa");
        System.out.println(validacao); System.out.println(g.informacoesTodas());
        try (HashEstendido h = HashEstendido.abrir(idx.resolveSibling("hash_diretorio.idx"), idx.resolveSibling("hash_buckets.idx"))) {
            exigir(h.getQuantidade() == 100000 && h.getQuantidadeInicial() == 100000 && h.getCapacidade() == 5000, "Hash oficial");
        }
        CrudIndexado c = new CrudIndexado(g);
        ArquivoSequencial oraculo = new ArquivoSequencial(db);
        int[] amostras = {1, 2, 50000, 99999, 100000, 100001};
        dados.bloquear = true;
        for (int id : amostras) {
            Carro esperado = oraculo.read(id);
            for (CrudIndexado.Metodo m : List.of(CrudIndexado.Metodo.BPLUS, CrudIndexado.Metodo.HASH)) {
                CrudIndexado.RegistroLocalizado r = c.localizar(m, id);
                exigir(java.util.Objects.equals(esperado, r == null ? null : r.getCarro()), "Amostra " + m + ": " + id);
            }
        }
        CrudIndexado.Consulta ano = c.consultar(2020, null), caracteristica = c.consultar(null, " AUTOMATIC "), combinada = c.consultar(2020, "automatic");
        List<Integer> anos = new ArrayList<>(), termos = new ArrayList<>(), juntos = new ArrayList<>();
        oraculo.percorrerAtivos((id, pos) -> {
            Carro carro = oraculo.readAtPosition(pos, id);
            boolean automatic = carro.getCaracteristicas().stream().anyMatch(s -> s.trim().equalsIgnoreCase("automatic"));
            if (carro.getAno() == 2020) anos.add(id);
            if (automatic) termos.add(id);
            if (carro.getAno() == 2020 && automatic) juntos.add(id);
        });
        anos.sort(Integer::compare); termos.sort(Integer::compare); juntos.sort(Integer::compare);
        exigir(ids(ano).equals(anos) && ids(caracteristica).equals(termos) && ids(combinada).equals(juntos), "Três consultas iguais ao oráculo independente");
        System.out.println("COMBINADA ano=2020; característica=automatic; resultados=" + combinada.total() + "; ORACLE=OK");
        dados.bloquear = false;
        System.out.println(new GerenciadorIndices(oraculo, idx).compararBuscas(amostras, 3));
        exigir(Arrays.equals(antes, digest(db)), "Base real preservada byte a byte");
        System.out.println("TP2 BASE REAL: OK; " + (reconstruir ? "construção" : "reabertura sem reconstrução") + "; cinco métodos de leitura e base preservada.");
    }

    private static List<Integer> ids(CrudIndexado.Consulta consulta) throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (int inicio = 0; inicio < consulta.total(); inicio += CrudIndexado.TAMANHO_PAGINA) {
            for (Carro carro : consulta.pagina(inicio)) ids.add(carro.getId());
        }
        return ids;
    }
    private static String global(GerenciadorIndices g) throws IOException {
        String r = g.validarTodos(); exigir(r.contains("Validação global: OK") && !r.contains("ERRO"), r); return r;
    }
    private static byte[] digest(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) d.update(b, 0, n); }
        return d.digest();
    }
    private static Carro carro(int id) { return new Carro(id, id == 0 ? "" : Carro.codigoPorId(id), "carro", LocalDate.of(2026, 9, 26), List.of("gas", "automatic"), 2020); }
    private static void exigir(boolean condicao, String mensagem) { if (!condicao) throw new AssertionError(mensagem); }
    private static void falha(Acao acao, String mensagem) throws Exception {
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { return; }
        throw new AssertionError("Deveria recusar: " + mensagem);
    }
    @FunctionalInterface private interface Acao { void executar() throws Exception; }
    private static final class DadosVigiados extends ArquivoSequencial {
        boolean bloquear; int leituras;
        DadosVigiados(Path p) { super(p); }
        @Override public Carro read(int id) { throw new AssertionError("Read sequencial indevido"); }
        @Override public Carro readAtPosition(long pos, int id) throws IOException { leituras++; return super.readAtPosition(pos, id); }
        @Override public void percorrerAtivos(VisitanteRegistro v) throws IOException {
            if (bloquear) throw new AssertionError("Varredura durante CRUD indexado"); super.percorrerAtivos(v);
        }
        @Override public List<Carro> listarAtivos() throws IOException {
            if (bloquear) throw new AssertionError("Materialização da base inteira"); return super.listarAtivos();
        }
    }
}
