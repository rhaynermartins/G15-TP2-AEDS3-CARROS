import index.hash.HashEstendido;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Capacidade pequena deriva de uma população inicial controlada de 40 registros. */
public class TesteHash {
    private static Path raiz;
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("build-test"));
        raiz = Files.createTempDirectory(Path.of("build-test"), "hash-");
        exigir(HashEstendido.capacidadePara(100000) == 5000, "Capacidade oficial calculada");
        exigir(HashEstendido.capacidadePara(1) == 1 && HashEstendido.capacidadePara(21) == 2, "Arredondamento");
        for (int p = 1; p <= 3; p++) for (int id : new int[]{0, 1, 2, 5, 17, 100000, Integer.MAX_VALUE}) {
            exigir(HashEstendido.hash(id, p) == id % (1 << p), "h(k)=k mod 2^p");
        }
        falha(() -> HashEstendido.capacidadePara(0), "População inicial nula");
        falha(() -> HashEstendido.hash(-1, 1), "Chave negativa");
        Path d = raiz.resolve("diretorio.idx"), b = raiz.resolve("buckets.idx");
        int[] ids = {1, 3, 5, 7, 9, 17};
        try (HashEstendido h = HashEstendido.criar(d, b, 40)) {
            exigir(h.getProfundidadeGlobal() == 1 && h.getQuantidadeBuckets() == 2, "Estado inicial");
            exigir(h.buscar(10) == -1, "Busca vazia");
            for (int id : ids) { h.inserir(id, id * 100L); h.validar(null); }
            exigir(h.getProfundidadeGlobal() == 4 && h.getQuantidadeBuckets() == 5, "Splits e duplicações");
            Set<Integer> locais = new HashSet<>();
            try (RandomAccessFile f = new RandomAccessFile(b.toFile(), "r")) {
                for (int i = 0; i < h.getQuantidadeBuckets(); i++) { f.seek(24 + i * 32L); locais.add(f.readInt()); }
            }
            exigir(locais.equals(Set.of(1, 2, 3, 4)), "Profundidades locais diferentes e aliases");
            falha(() -> h.inserir(1, 999), "ID duplicado");
            exigir(h.getQuantidade() == ids.length, "Duplicata não altera contagem");
            exigir(h.atualizarPosicao(5, 999) && !h.atualizarPosicao(100, 999), "Atualizar existente/ausente");
            System.out.println(h.validar(null));
        }
        try (HashEstendido h = HashEstendido.abrir(d, b)) {
            for (int id : ids) exigir(h.buscar(id) == (id == 5 ? 999 : id * 100L), "Persistência de posições");
            exigir(h.getQuantidadeInicial() == 40 && h.getCapacidade() == 2, "Configuração persistida");
            for (int id : ids) { exigir(h.remover(id) && h.buscar(id) == -1, "Remover"); h.validar(null); }
            exigir(!h.remover(1) && h.getQuantidade() == 0, "Ausente e vazio");
            exigir(h.getProfundidadeGlobal() == 4 && h.getQuantidadeBuckets() == 5, "Delete não reduz diretório");
            h.inserir(17, 1700);
        }
        intercaladas();
        corrupcao(d, b);
        System.out.println("TODOS OS TESTES HASH PASSARAM: módulo/split/duplicação/locais/CRUD/reabertura/corrupção.");
    }

    private static void intercaladas() throws Exception {
        Path d = raiz.resolve("aleatorio-dir.idx"), b = raiz.resolve("aleatorio-buckets.idx");
        Map<Integer, Long> esperado = new TreeMap<>(); Random r = new Random(215);
        try (HashEstendido h = HashEstendido.criar(d, b, 40)) {
            for (int rodada = 0; rodada < 1200; rodada++) {
                int id = 1 + r.nextInt(160), op = r.nextInt(3); long pos = 4 + rodada * 37L;
                if (op == 0 && !esperado.containsKey(id)) { h.inserir(id, pos); esperado.put(id, pos); }
                else if (op == 1) exigir(h.remover(id) == (esperado.remove(id) != null), "Remove com oráculo");
                else if (op == 2) {
                    exigir(h.atualizarPosicao(id, pos) == esperado.containsKey(id), "Update com oráculo");
                    if (esperado.containsKey(id)) esperado.put(id, pos);
                }
                Map<Integer, Long> obtido = new TreeMap<>(); h.validar(obtido::put);
                exigir(obtido.equals(esperado), "Oráculo após cada operação");
                for (int chave = 1; chave <= 160; chave++) exigir(h.buscar(chave) == esperado.getOrDefault(chave, -1L), "Busca com oráculo");
            }
        }
        try (HashEstendido h = HashEstendido.abrir(d, b)) {
            Map<Integer, Long> obtido = new TreeMap<>(); h.validar(obtido::put);
            exigir(obtido.equals(esperado), "Oráculo após reabertura");
        }
        System.out.println("Hash: 1200 operações intercaladas comparadas com oráculo: OK");
    }

    private static void corrupcao(Path originalD, Path originalB) throws Exception {
        for (int caso = 0; caso < 8; caso++) {
            Path d = raiz.resolve("corrompido-dir-" + caso), b = raiz.resolve("corrompido-buckets-" + caso);
            Files.copy(originalD, d); Files.copy(originalB, b);
            try (RandomAccessFile dir = new RandomAccessFile(d.toFile(), "rw"); RandomAccessFile bucket = new RandomAccessFile(b.toFile(), "rw")) {
                switch (caso) {
                    case 0: dir.seek(0); dir.writeInt(0); break;
                    case 1: dir.seek(40); dir.writeInt(1); break;
                    case 2: dir.seek(64); dir.writeLong(25); break;
                    case 3: dir.seek(12); dir.writeInt(5000); break;
                    case 4: bucket.seek(8); bucket.writeLong(0); break;
                    case 5: bucket.setLength(bucket.length() - 1); break;
                    case 6: bucket.seek(24); bucket.writeInt(99); break;
                    case 7: dir.seek(32); dir.writeLong(2); break;
                    default: throw new AssertionError();
                }
            }
            falha(() -> { try (HashEstendido h = HashEstendido.abrir(d, b)) { h.validar(null); } }, "Corrupção " + caso);
        }
    }

    private static void exigir(boolean condicao, String mensagem) { if (!condicao) throw new AssertionError(mensagem); }
    private static void falha(Acao acao, String mensagem) throws Exception {
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { return; }
        throw new AssertionError("Deveria recusar: " + mensagem);
    }
    @FunctionalInterface private interface Acao { void executar() throws Exception; }
}
