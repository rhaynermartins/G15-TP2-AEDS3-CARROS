import index.lista.ListaInvertida;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class TesteListas {
    private static Path raiz;
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("build-test"));
        raiz = Files.createTempDirectory(Path.of("build-test"), "listas-");
        Path ano = raiz.resolve("ano.idx"), caracteristicas = raiz.resolve("caracteristicas.idx");
        try (ListaInvertida a = ListaInvertida.criar(ano, ListaInvertida.ANO);
             ListaInvertida c = ListaInvertida.criar(caracteristicas, ListaInvertida.CARACTERISTICA)) {
            exigir(a.buscar(2020).isEmpty() && c.buscar("gas").isEmpty(), "Listas vazias");
            for (int id : new int[]{8, 2, 6, 1}) a.inserir(2020, id, id * 100L);
            a.inserir(2021, 3, 300);
            c.inserir("  AUTOMATIC  ", 6, 600); c.inserir("automatic", 2, 200); c.inserir("Automatic", 3, 300);
            c.inserir("gas", 2, 200); c.inserir("sedan", 2, 200);
            exigir(ids(a.buscar(2020)).equals(List.of(1, 2, 6, 8)), "Postings ordenados após inserção fora de ordem");
            exigir(ids(c.buscar(" AUTOMATIC ")).equals(List.of(2, 3, 6)), "Normalização consistente");
            exigir(ids(ListaInvertida.intersecao(a.buscar(2020), c.buscar("automatic"))).equals(List.of(2, 6)), "Interseção ordenada");
            exigir(ListaInvertida.intersecao(a.buscar(1900), c.buscar("automatic")).isEmpty(), "Interseção vazia");
            falha(() -> c.inserir("Automatic", 2, 900), "Posting duplicado normalizado");
            falha(() -> c.inserir("  ", 4, 400), "Termo vazio");
            falha(() -> a.inserir("2020", 4, 400), "Ano deve ser int");
            exigir(c.getQuantidadePostings() == 5 && c.getQuantidadeTermos() == 3, "Sem duplicação de termos/postings");
            exigir(a.atualizarPosicao(2020, 2, 222) && c.atualizarPosicao("automatic", 2, 222), "Posições atualizadas");
            exigir(c.atualizarPosicao("gas", 2, 222) && c.atualizarPosicao("sedan", 2, 222), "Múltiplas características");
            a.validar(null); c.validar(null);
        }
        try (ListaInvertida a = ListaInvertida.abrir(ano); ListaInvertida c = ListaInvertida.abrir(caracteristicas)) {
            exigir(a.getTipo() == ListaInvertida.ANO && c.getTipo() == ListaInvertida.CARACTERISTICA, "Tipos persistidos");
            exigir(a.buscar(2020).get(1).posicao == 222 && c.buscar("gas").get(0).posicao == 222, "Posição persistida");
            exigir(ids(ListaInvertida.intersecao(a.buscar(2020), c.buscar("automatic"))).equals(List.of(2, 6)), "Interseção após reabrir");
            exigir(a.remover(2020, 1) && a.remover(2020, 6) && a.remover(2020, 8), "Remove cabeça/meio/cauda");
            exigir(a.remover(2020, 2) && a.buscar(2020).isEmpty(), "Remove último posting");
            exigir(!a.remover(2020, 2) && !c.atualizarPosicao("ausente", 7, 700), "Ausentes");
            a.inserir(2020, 10, 1000);
            exigir(ids(a.buscar(2020)).equals(List.of(10)), "Reutiliza termo esvaziado");
            a.inserir(2020, 2, 999);
            falha(() -> ListaInvertida.intersecao(a.buscar(2020), c.buscar("automatic")), "Posições divergentes");
            a.validar(null); c.validar(null);
        }
        intercaladas();
        corrupcao(caracteristicas);
        System.out.println("TODOS OS TESTES LISTAS PASSARAM: ano/características/normalização/interseção/persistência.");
    }

    private static void intercaladas() throws Exception {
        for (int tipo : new int[]{ListaInvertida.ANO, ListaInvertida.CARACTERISTICA}) {
            Path p = raiz.resolve("aleatoria-" + tipo); Map<Integer, Long> esperado = new TreeMap<>();
            Object termo = tipo == ListaInvertida.ANO ? 2020 : "gas";
            Random r = new Random(315);
            try (ListaInvertida lista = ListaInvertida.criar(p, tipo)) {
                for (int rodada = 0; rodada < 800; rodada++) {
                    int id = 1 + r.nextInt(90), op = r.nextInt(3); long pos = 4 + rodada * 29L;
                    if (op == 0 && !esperado.containsKey(id)) { lista.inserir(termo, id, pos); esperado.put(id, pos); }
                    else if (op == 1) exigir(lista.remover(termo, id) == (esperado.remove(id) != null), "Remoção com oráculo");
                    else if (op == 2) {
                        exigir(lista.atualizarPosicao(termo, id, pos) == esperado.containsKey(id), "Update com oráculo");
                        if (esperado.containsKey(id)) esperado.put(id, pos);
                    }
                    Map<Integer, Long> obtido = new TreeMap<>(); lista.validar((t, chave, endereco) -> obtido.put(chave, endereco));
                    exigir(obtido.equals(esperado), "Conteúdo contra oráculo");
                }
            }
            try (ListaInvertida lista = ListaInvertida.abrir(p)) {
                Map<Integer, Long> obtido = new TreeMap<>(); lista.validar((t, chave, endereco) -> obtido.put(chave, endereco));
                exigir(obtido.equals(esperado), "Persistência contra oráculo");
            }
        }
        System.out.println("Listas: 800 operações por tipo comparadas com oráculo: OK");
    }

    private static void corrupcao(Path original) throws Exception {
        for (int caso = 0; caso < 6; caso++) {
            Path p = raiz.resolve("corrompida-" + caso); Files.copy(original, p);
            try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
                f.seek(32); long termo = f.readLong();
                switch (caso) {
                    case 0: f.seek(0); f.writeInt(0); break;
                    case 1: f.seek(12); f.writeInt(1); break;
                    case 2: f.seek(termo); f.writeLong(termo); break;
                    case 3: f.seek(termo + 8); f.writeLong(f.length()); break;
                    case 4: f.seek(termo + 8); long posting = f.readLong(); f.seek(posting + 12); f.writeLong(posting); break;
                    case 5: f.seek(24); f.writeLong(999); break;
                    default: throw new AssertionError();
                }
            }
            falha(() -> { try (ListaInvertida lista = ListaInvertida.abrir(p)) { lista.validar(null); } }, "Corrupção " + caso);
        }
    }

    private static List<Integer> ids(List<ListaInvertida.Posting> postings) {
        return postings.stream().map(p -> p.id).collect(Collectors.toList());
    }
    private static void exigir(boolean condicao, String mensagem) { if (!condicao) throw new AssertionError(mensagem); }
    private static void falha(Acao acao, String mensagem) throws Exception {
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { return; }
        throw new AssertionError("Deveria recusar: " + mensagem);
    }
    @FunctionalInterface private interface Acao { void executar() throws Exception; }
}
