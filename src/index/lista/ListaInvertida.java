package index.lista;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Uma lista por arquivo. Dicionário auxiliar de termos em RAM; postings sempre no disco.
 * Ano é int; característica usa UTF. Postings id/posição/próximo têm 20 bytes.
 */
public final class ListaInvertida implements Closeable {
    public static final int ANO = 1, CARACTERISTICA = 2, CABECALHO = 40;
    private static final int MAGIC = 0x4C495354;
    private final RandomAccessFile arquivo;
    private int tipo;
    private long total, primeiroTermo = -1;
    private boolean pendente;
    private final Map<Object, Termo> termos = new LinkedHashMap<>();

    public static String normalizar(String termo) {
        return termo == null ? "" : termo.trim().toLowerCase(Locale.ROOT);
    }
    public static ListaInvertida criar(Path caminho, int tipo) throws IOException {
        if (tipo != ANO && tipo != CARACTERISTICA) throw new IllegalArgumentException("Tipo de lista inválido.");
        if (caminho.getParent() != null) Files.createDirectories(caminho.getParent());
        Files.createFile(caminho);
        return new ListaInvertida(caminho, tipo);
    }
    public static ListaInvertida abrir(Path caminho) throws IOException {
        if (!Files.isRegularFile(caminho)) throw new IOException("Lista invertida ausente: " + caminho);
        return new ListaInvertida(caminho, 0);
    }
    private ListaInvertida(Path caminho, int novoTipo) throws IOException {
        arquivo = new RandomAccessFile(caminho.toFile(), "rw");
        try {
            if (novoTipo != 0) { tipo = novoTipo; cabecalho(); return; }
            if (arquivo.length() < CABECALHO || arquivo.readInt() != MAGIC || arquivo.readInt() != 1) {
                throw erro("Cabeçalho de lista incompatível");
            }
            tipo = arquivo.readInt(); pendente = arquivo.readInt() != 0;
            long quantidadeTermos = arquivo.readLong(); total = arquivo.readLong(); primeiroTermo = arquivo.readLong();
            if ((tipo != ANO && tipo != CARACTERISTICA) || pendente || total < 0 || quantidadeTermos < 0
                    || quantidadeTermos > arquivo.length() / 34 || total > arquivo.length() / 20) {
                throw erro("Metadados de lista inválidos");
            }
            Set<Long> vistos = new HashSet<>(); long pos = primeiroTermo;
            while (pos != -1) {
                endereco(pos, 34);
                if (!vistos.add(pos)) throw erro("Ciclo no dicionário de termos");
                arquivo.seek(pos);
                Termo t = new Termo(pos, arquivo.readLong());
                t.primeiro = arquivo.readLong(); t.ultimo = arquivo.readLong(); t.n = arquivo.readLong();
                Object chave = tipo == ANO ? Integer.valueOf(arquivo.readInt()) : arquivo.readUTF();
                if (t.n < 0 || t.n > total || (t.n == 0 && (t.primeiro != -1 || t.ultimo != -1))
                        || (t.n > 0 && (t.primeiro == -1 || t.ultimo == -1))
                        || !chave.equals(chave(chave)) || termos.put(chave, t) != null) {
                    throw erro("Termo inválido ou duplicado");
                }
                pos = t.proximo;
            }
            if (termos.size() != quantidadeTermos) throw erro("Quantidade de termos divergente");
        } catch (IOException | RuntimeException e) { arquivo.close(); throw e; }
    }
    private IOException erro(String msg) { return new IOException(msg + "; reconstrua as listas."); }
    private void pronto() throws IOException { if (pendente) throw erro("Operação de lista incompleta"); }
    private void endereco(long pos, int bytes) throws IOException {
        if (pos < CABECALHO || pos > arquivo.length() - bytes) throw erro("Ponteiro da lista inválido");
    }
    private Object chave(Object chave) {
        if (tipo == ANO && chave instanceof Integer) return chave;
        if (tipo == CARACTERISTICA && chave instanceof String) return normalizar((String) chave);
        throw new IllegalArgumentException("Use ano int ou característica String conforme o tipo da lista.");
    }
    private void cabecalho() throws IOException {
        arquivo.seek(0); arquivo.writeInt(MAGIC); arquivo.writeInt(1); arquivo.writeInt(tipo);
        arquivo.writeInt(pendente ? 1 : 0); arquivo.writeLong(termos.size());
        arquivo.writeLong(total); arquivo.writeLong(primeiroTermo);
    }
    private void iniciar() throws IOException { pronto(); pendente = true; cabecalho(); }
    private void concluir() throws IOException {
        pendente = false;
        try { cabecalho(); } catch (IOException e) { pendente = true; throw e; }
    }
    private void gravarTermo(Termo t) throws IOException {
        arquivo.seek(t.endereco); arquivo.writeLong(t.proximo); arquivo.writeLong(t.primeiro);
        arquivo.writeLong(t.ultimo); arquivo.writeLong(t.n);
    }
    private Termo criarTermo(Object chave) throws IOException {
        Termo t = new Termo(arquivo.length(), primeiroTermo);
        gravarTermo(t);
        if (tipo == ANO) arquivo.writeInt((Integer) chave); else arquivo.writeUTF((String) chave);
        termos.put(chave, t); primeiroTermo = t.endereco;
        return t;
    }
    private Posting ler(long pos) throws IOException {
        endereco(pos, 20); arquivo.seek(pos);
        Posting p = new Posting(arquivo.readInt(), arquivo.readLong(), arquivo.readLong());
        if (p.id <= 0 || p.posicao < 4) throw erro("Posting inválido");
        return p;
    }
    private void proximo(long pos, long seguinte) throws IOException {
        arquivo.seek(pos + 12); arquivo.writeLong(seguinte);
    }
    /** Encontra o predecessor, explorando o tail para append crescente O(1). */
    private Cursor localizar(Termo t, int id) throws IOException {
        if (t.n > 0) {
            Posting fim = ler(t.ultimo);
            if (fim.proximo != -1) throw erro("Cauda da lista inválida");
            if (id > fim.id) return new Cursor(t.ultimo, -1, null);
        }
        long atual = t.primeiro, anterior = -1, passos = 0; int ultimoId = 0;
        while (atual != -1) {
            if (++passos > t.n) throw erro("Ciclo ou contagem divergente");
            Posting p = ler(atual);
            if (p.id <= ultimoId) throw erro("Postings fora de ordem");
            if (p.id >= id) return new Cursor(anterior, atual, p);
            anterior = atual; atual = p.proximo; ultimoId = p.id;
        }
        return new Cursor(anterior, -1, null);
    }
    public void inserir(Object chave, int id, long posicao) throws IOException {
        pronto(); chave = chave(chave);
        if (id <= 0 || posicao < 4 || chave.equals("")) throw new IllegalArgumentException("Posting/termo inválido.");
        Termo t = termos.get(chave);
        Cursor c = t == null ? new Cursor(-1, -1, null) : localizar(t, id);
        if (c.posting != null && c.posting.id == id) throw new IOException("Posting duplicado: " + id);
        iniciar();
        if (t == null) t = criarTermo(chave);
        long novo = arquivo.length(); arquivo.seek(novo);
        arquivo.writeInt(id); arquivo.writeLong(posicao); arquivo.writeLong(c.atual);
        if (c.anterior == -1) t.primeiro = novo; else proximo(c.anterior, novo);
        if (c.atual == -1) t.ultimo = novo;
        t.n++; total++; gravarTermo(t); concluir();
    }
    public boolean atualizarPosicao(Object chave, int id, long posicao) throws IOException {
        pronto();
        if (posicao < 4) throw new IllegalArgumentException("Posição inválida.");
        Termo t = termos.get(chave(chave)); if (t == null) return false;
        Cursor c = localizar(t, id);
        if (c.posting == null || c.posting.id != id) return false;
        iniciar(); arquivo.seek(c.atual + 4); arquivo.writeLong(posicao); concluir(); return true;
    }
    public boolean remover(Object chave, int id) throws IOException {
        pronto(); Termo t = termos.get(chave(chave)); if (t == null) return false;
        Cursor c = localizar(t, id);
        if (c.posting == null || c.posting.id != id) return false;
        iniciar();
        if (c.anterior == -1) t.primeiro = c.posting.proximo; else proximo(c.anterior, c.posting.proximo);
        if (t.ultimo == c.atual) t.ultimo = c.anterior;
        arquivo.seek(c.atual); arquivo.writeInt(-1);
        t.n--; total--; gravarTermo(t); concluir(); return true;
    }
    public List<Posting> buscar(Object chave) throws IOException {
        pronto(); List<Posting> resultado = new ArrayList<>();
        Termo t = termos.get(chave(chave));
        if (t != null) percorrer(t, (id, pos) -> resultado.add(new Posting(id, pos, -1)));
        return resultado;
    }
    private void percorrer(Termo t, VisitantePosting visitante) throws IOException {
        long atual = t.primeiro, ultimo = -1, n = 0; int anterior = 0;
        while (atual != -1) {
            if (++n > t.n) throw erro("Ciclo ou excesso de postings");
            Posting p = ler(atual);
            if (p.id <= anterior) throw erro("Posting duplicado ou fora de ordem");
            visitante.visitar(p.id, p.posicao);
            anterior = p.id; ultimo = atual; atual = p.proximo;
        }
        if (n != t.n || ultimo != t.ultimo) throw erro("Contagem/cauda de postings divergente");
    }
    /** Duas listas já ordenadas: O(n+m), sem acessar dados.db. */
    public static List<Posting> intersecao(List<Posting> a, List<Posting> b) throws IOException {
        List<Posting> resultado = new ArrayList<>(); int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            Posting x = a.get(i), y = b.get(j);
            if (x.id < y.id) i++;
            else if (x.id > y.id) j++;
            else {
                if (x.posicao != y.posicao) throw new IOException("Posições divergentes na interseção; reconstrua.");
                resultado.add(x); i++; j++;
            }
        }
        return resultado;
    }
    @FunctionalInterface public interface Visitante {
        void visitar(Object termo, int id, long posicao) throws IOException;
    }
    @FunctionalInterface private interface VisitantePosting { void visitar(int id, long posicao) throws IOException; }
    public String validar(Visitante visitante) throws IOException {
        pronto(); long[] n = {0};
        for (Map.Entry<Object, Termo> e : termos.entrySet()) {
            percorrer(e.getValue(), (id, pos) -> {
                if (visitante != null) visitante.visitar(e.getKey(), id, pos);
                n[0]++;
            });
        }
        if (n[0] != total) throw erro("Total de postings divergente");
        return (tipo == ANO ? "Lista Ano" : "Lista Características") + ": OK; termos="
                + termos.size() + "; postings=" + total;
    }
    public int getTipo() { return tipo; }
    public int getQuantidadeTermos() { return termos.size(); }
    public long getQuantidadePostings() { return total; }
    public List<Object> termos() { return new ArrayList<>(termos.keySet()); }
    @Override public void close() throws IOException {
        try { arquivo.getFD().sync(); } finally { arquivo.close(); }
    }
    public static final class Posting {
        public final int id;
        public final long posicao;
        private final long proximo;
        private Posting(int id, long posicao, long proximo) { this.id = id; this.posicao = posicao; this.proximo = proximo; }
    }
    private static final class Termo {
        final long endereco, proximo;
        long primeiro = -1, ultimo = -1, n;
        Termo(long endereco, long proximo) { this.endereco = endereco; this.proximo = proximo; }
    }
    private static final class Cursor {
        final long anterior, atual; final Posting posting;
        Cursor(long anterior, long atual, Posting posting) { this.anterior = anterior; this.atual = atual; this.posting = posting; }
    }
}
