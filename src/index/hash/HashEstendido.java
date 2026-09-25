package index.hash;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Diretório em disco, buckets fixos e h(k)=k mod 2^p. Um escritor por vez. */
public final class HashEstendido implements Closeable {
    public static final int CABECALHO_DIRETORIO = 64, CABECALHO_BUCKETS = 24;
    private static final int MAGIC = 0x48455354, VERSAO = 1, MAX_PROFUNDIDADE = 24;
    private final RandomAccessFile diretorio, buckets;
    private int profundidade, capacidade, tamanhoBucket;
    private long quantidadeInicial, quantidadeBuckets, entradas, identidadeA, identidadeB;
    private boolean pendente;

    /** Arredonda para cima bases não múltiplas de 20; ao menos uma entrada. */
    public static int capacidadePara(long quantidadeInicial) {
        if (quantidadeInicial < 1 || quantidadeInicial > 20_000_000L) {
            throw new IllegalArgumentException("Quantidade inicial deve estar entre 1 e 20.000.000.");
        }
        return (int) ((quantidadeInicial + 19) / 20);
    }

    public static int hash(int id, int p) {
        if (id < 0 || p < 1 || p > MAX_PROFUNDIDADE) throw new IllegalArgumentException("ID/profundidade inválidos.");
        return (int) (id % (1L << p));
    }

    public static HashEstendido criar(Path dir, Path dados, long inicial) throws IOException {
        capacidadePara(inicial);
        if (dir.toAbsolutePath().normalize().equals(dados.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Diretório e buckets devem ser arquivos distintos.");
        }
        if (Files.exists(dir) || Files.exists(dados)) throw new IOException("Destino do Hash já existe.");
        if (dir.getParent() != null) Files.createDirectories(dir.getParent());
        if (dados.getParent() != null) Files.createDirectories(dados.getParent());
        Files.createFile(dir);
        Files.createFile(dados);
        return new HashEstendido(dir, dados, inicial);
    }

    public static HashEstendido abrir(Path dir, Path dados) throws IOException {
        if (!Files.isRegularFile(dir) || !Files.isRegularFile(dados)) throw new IOException("Arquivos Hash ausentes.");
        return new HashEstendido(dir, dados, 0);
    }

    private HashEstendido(Path dir, Path dados, long inicial) throws IOException {
        diretorio = new RandomAccessFile(dir.toFile(), "rw");
        RandomAccessFile aberto;
        try { aberto = new RandomAccessFile(dados.toFile(), "rw"); }
        catch (IOException e) { diretorio.close(); throw e; }
        buckets = aberto;
        try {
            if (inicial > 0) {
                quantidadeInicial = inicial;
                capacidade = capacidadePara(inicial);
                tamanhoBucket = 8 + 12 * capacidade;
                profundidade = 1;
                UUID identidade = UUID.randomUUID();
                identidadeA = identidade.getMostSignificantBits();
                identidadeB = identidade.getLeastSignificantBits();
                buckets.writeInt(MAGIC); buckets.writeInt(VERSAO);
                buckets.writeLong(identidadeA); buckets.writeLong(identidadeB);
                for (int i = 0; i < 2; i++) {
                    Bucket b = novoBucket(1);
                    gravarBucket(b);
                    escreverPonteiro(i, b.posicao);
                }
                cabecalho();
            } else {
                if (diretorio.length() < 64 || diretorio.readInt() != MAGIC || diretorio.readInt() != VERSAO) {
                    throw erro("Cabeçalho Hash incompatível");
                }
                profundidade = diretorio.readInt(); capacidade = diretorio.readInt();
                quantidadeInicial = diretorio.readLong(); quantidadeBuckets = diretorio.readLong();
                entradas = diretorio.readLong(); pendente = diretorio.readInt() != 0;
                tamanhoBucket = diretorio.readInt();
                identidadeA = diretorio.readLong(); identidadeB = diretorio.readLong();
                if (profundidade < 1 || profundidade > MAX_PROFUNDIDADE || pendente
                        || quantidadeInicial < 1 || quantidadeInicial > 20_000_000L
                        || capacidade != capacidadePara(quantidadeInicial) || tamanhoBucket != 8 + 12 * capacidade
                        || quantidadeBuckets < 2 || quantidadeBuckets > (1L << profundidade)
                        || entradas < 0 || entradas > quantidadeBuckets * capacidade
                        || diretorio.length() != 64 + 8 * (1L << profundidade)
                        || buckets.length() != 24 + tamanhoBucket * quantidadeBuckets) throw erro("Metadados Hash inválidos");
                if (buckets.readInt() != MAGIC || buckets.readInt() != VERSAO
                        || buckets.readLong() != identidadeA || buckets.readLong() != identidadeB) {
                    throw erro("Diretório e buckets pertencem a gerações diferentes");
                }
            }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }

    private IOException erro(String msg) { return new IOException(msg + "; reconstrua o Hash."); }
    private void pronto() throws IOException { if (pendente) throw erro("Operação Hash incompleta"); }
    private void iniciar() throws IOException { pronto(); pendente = true; cabecalho(); }
    private void concluir() throws IOException {
        pendente = false;
        try { cabecalho(); } catch (IOException e) { pendente = true; throw e; }
    }
    private void cabecalho() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(64);
        b.putInt(MAGIC).putInt(VERSAO).putInt(profundidade).putInt(capacidade);
        b.putLong(quantidadeInicial).putLong(quantidadeBuckets).putLong(entradas);
        b.putInt(pendente ? 1 : 0).putInt(tamanhoBucket).putLong(identidadeA).putLong(identidadeB);
        diretorio.seek(0); diretorio.write(b.array());
    }
    private long ponteiro(int i) throws IOException {
        diretorio.seek(64L + 8L * i);
        long pos = diretorio.readLong();
        if (pos < 24 || (pos - 24) % tamanhoBucket != 0 || (pos - 24) / tamanhoBucket >= quantidadeBuckets) {
            throw erro("Ponteiro de bucket inválido");
        }
        return pos;
    }
    private void escreverPonteiro(int i, long pos) throws IOException {
        diretorio.seek(64L + 8L * i); diretorio.writeLong(pos);
    }
    private Bucket novoBucket(int local) {
        Bucket b = new Bucket(24 + quantidadeBuckets++ * tamanhoBucket, local);
        return b;
    }
    private Bucket lerBucket(long pos) throws IOException {
        buckets.seek(pos);
        int local = buckets.readInt(), n = buckets.readInt();
        if (local < 1 || local > profundidade || n < 0 || n > capacidade) throw erro("Bucket inválido");
        Bucket b = new Bucket(pos, local);
        b.n = n;
        byte[] pares = new byte[12 * n]; buckets.readFully(pares);
        ByteBuffer bytes = ByteBuffer.wrap(pares);
        for (int i = 0; i < n; i++) {
            b.ids[i] = bytes.getInt(); b.posicoes[i] = bytes.getLong();
            if (b.ids[i] <= 0 || b.posicoes[i] < 4) throw erro("Entrada Hash inválida");
        }
        return b;
    }
    private void gravarBucket(Bucket b) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(tamanhoBucket);
        bytes.putInt(b.local).putInt(b.n);
        for (int i = 0; i < b.n; i++) bytes.putInt(b.ids[i]).putLong(b.posicoes[i]);
        buckets.seek(b.posicao); buckets.write(bytes.array());
    }
    private int localizar(Bucket b, int id) {
        for (int i = 0; i < b.n; i++) if (b.ids[i] == id) return i;
        return -1;
    }
    public long buscar(int id) throws IOException {
        pronto();
        if (id <= 0) return -1;
        Bucket b = lerBucket(ponteiro(hash(id, profundidade)));
        int i = localizar(b, id);
        return i < 0 ? -1 : b.posicoes[i];
    }
    public void inserir(int id, long pos) throws IOException {
        pronto();
        if (id <= 0 || pos < 4) throw new IllegalArgumentException("ID/posição inválidos.");
        Bucket b = lerBucket(ponteiro(hash(id, profundidade)));
        if (localizar(b, id) >= 0) throw new IOException("ID Hash duplicado: " + id);
        iniciar();
        while (b.n == capacidade) {
            dividir(b);
            b = lerBucket(ponteiro(hash(id, profundidade)));
        }
        // Append dentro do bucket sem reescrever os slots não utilizados.
        buckets.seek(b.posicao + 8 + 12L * b.n);
        buckets.writeInt(id); buckets.writeLong(pos);
        buckets.seek(b.posicao + 4); buckets.writeInt(b.n + 1);
        entradas++;
        concluir();
    }
    private void dividir(Bucket b) throws IOException {
        if (b.local == profundidade) {
            if (profundidade == MAX_PROFUNDIDADE) throw erro("Limite operacional de profundidade atingido");
            int anterior = 1 << profundidade;
            for (int i = 0; i < anterior; i++) escreverPonteiro(i + anterior, ponteiro(i));
            profundidade++;
        }
        int bit = 1 << b.local;
        Bucket novo = novoBucket(++b.local);
        int quantidade = b.n; b.n = 0;
        for (int i = 0; i < quantidade; i++) {
            int id = b.ids[i]; long pos = b.posicoes[i];
            Bucket destino = (hash(id, b.local) & bit) == 0 ? b : novo;
            destino.ids[destino.n] = id; destino.posicoes[destino.n++] = pos;
        }
        gravarBucket(b); gravarBucket(novo);
        // Só os aliases do bucket dividido com o novo bit ligado mudam de endereço.
        for (int i = 0; i < (1 << profundidade); i++) {
            if ((i & bit) != 0 && ponteiro(i) == b.posicao) escreverPonteiro(i, novo.posicao);
        }
    }
    public boolean atualizarPosicao(int id, long pos) throws IOException {
        pronto();
        if (pos < 4) throw new IllegalArgumentException("Posição inválida.");
        if (id <= 0) return false;
        Bucket b = lerBucket(ponteiro(hash(id, profundidade))); int i = localizar(b, id);
        if (i < 0) return false;
        iniciar(); buckets.seek(b.posicao + 8 + 12L * i + 4); buckets.writeLong(pos); concluir();
        return true;
    }
    /** Delete compacta somente o bucket; não funde buckets nem reduz o diretório. */
    public boolean remover(int id) throws IOException {
        pronto(); if (id <= 0) return false;
        Bucket b = lerBucket(ponteiro(hash(id, profundidade))); int i = localizar(b, id);
        if (i < 0) return false;
        iniciar();
        b.ids[i] = b.ids[b.n - 1]; b.posicoes[i] = b.posicoes[b.n - 1]; b.n--;
        gravarBucket(b); entradas--; concluir(); return true;
    }
    @FunctionalInterface public interface Visitante { void visitar(int id, long posicao) throws IOException; }
    public String validar(Visitante visitante) throws IOException {
        pronto();
        Map<Long, Integer> referencias = new HashMap<>();
        Map<Long, Integer> prefixos = new HashMap<>();
        for (int i = 0; i < (1 << profundidade); i++) {
            long pos = ponteiro(i);
            referencias.put(pos, referencias.getOrDefault(pos, 0) + 1);
            prefixos.putIfAbsent(pos, i);
        }
        if (referencias.size() != quantidadeBuckets) throw erro("Buckets órfãos");
        Set<Integer> ids = new HashSet<>(), locais = new HashSet<>();
        long total = 0;
        for (Map.Entry<Long, Integer> ref : referencias.entrySet()) {
            Bucket b = lerBucket(ref.getKey()); locais.add(b.local);
            int prefixo = hash(prefixos.get(b.posicao), b.local);
            if (ref.getValue() != (1 << (profundidade - b.local))) throw erro("Número de aliases incoerente");
            for (int i = prefixo; i < (1 << profundidade); i += 1 << b.local) {
                if (ponteiro(i) != b.posicao) throw erro("Prefixo do bucket incoerente");
            }
            for (int i = 0; i < b.n; i++) {
                if (!ids.add(b.ids[i]) || ponteiro(hash(b.ids[i], profundidade)) != b.posicao) {
                    throw erro("ID duplicado ou no bucket incorreto");
                }
                if (visitante != null) visitante.visitar(b.ids[i], b.posicoes[i]);
                total++;
            }
        }
        if (total != entradas) throw erro("Contagem Hash divergente");
        return informacoes() + "; profundidades locais=" + locais + "; Hash: OK";
    }
    public String informacoes() {
        return "Hash entradas=" + entradas + "; p=" + profundidade + "; buckets=" + quantidadeBuckets
                + "; capacidade=" + capacidade + "; quantidade inicial=" + quantidadeInicial;
    }
    public int getProfundidadeGlobal() { return profundidade; }
    public int getCapacidade() { return capacidade; }
    public long getQuantidadeInicial() { return quantidadeInicial; }
    public long getQuantidade() { return entradas; }
    public long getQuantidadeBuckets() { return quantidadeBuckets; }
    @Override public void close() throws IOException {
        try { diretorio.getFD().sync(); buckets.getFD().sync(); }
        finally { try { diretorio.close(); } finally { buckets.close(); } }
    }
    private final class Bucket {
        final long posicao; int local, n;
        final int[] ids = new int[capacidade]; final long[] posicoes = new long[capacidade];
        Bucket(long posicao, int local) { this.posicao = posicao; this.local = local; }
    }
}
