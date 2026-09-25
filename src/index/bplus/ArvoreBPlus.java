package index.bplus;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** B+ persistente. Ordem m: até m filhos internos e m-1 entradas por folha.
 * Somente as páginas do caminho visitado e irmãos são lidos durante uma operação.
 * Ponteiros são offsets absolutos no arquivo; -1 representa ausência.
 */
public final class ArvoreBPlus implements Closeable {
    private static final int MAGIC = 0x42504C53;
    private static final int VERSAO = 1;
    public static final int CABECALHO = 64;
    private final RandomAccessFile arquivo;
    private final int ordem;
    private final int tamanhoPagina;
    private long raiz, primeiraFolha, paginas, quantidade, livre = -1;
    private boolean pendente;
    private int splitsFolha, splitsInterno, redistribuicoes, fusoes;

    public static void validarOrdem(int ordem) {
        if (ordem < 3 || ordem > 4096) {
            throw new IllegalArgumentException("Ordem deve estar entre 3 e 4096.");
        }
    }

    /** Não sobrescreve um índice existente: reconstrução deve usar arquivo temporário. */
    public static ArvoreBPlus criar(Path caminho, int ordem) throws IOException {
        validarOrdem(ordem);
        if (caminho.getParent() != null) Files.createDirectories(caminho.getParent());
        Files.createFile(caminho);
        return new ArvoreBPlus(caminho, ordem);
    }

    public static ArvoreBPlus abrir(Path caminho) throws IOException {
        if (!Files.isRegularFile(caminho)) throw new IOException("Índice ausente: " + caminho);
        return new ArvoreBPlus(caminho, 0);
    }

    private ArvoreBPlus(Path caminho, int novaOrdem) throws IOException {
        arquivo = new RandomAccessFile(caminho.toFile(), "rw");
        try {
            if (novaOrdem != 0) {
                ordem = novaOrdem;
                tamanhoPagina = 13 + 4 * (ordem - 1) + 8 * ordem;
                Pagina folha = alocar(true);
                raiz = primeiraFolha = folha.endereco;
                gravar(folha);
                cabecalho();
            } else {
                if (arquivo.length() < CABECALHO || arquivo.readInt() != MAGIC
                        || arquivo.readInt() != VERSAO) throw falha("Cabeçalho incompatível");
                ordem = arquivo.readInt();
                if (ordem < 3 || ordem > 4096) throw falha("Ordem inválida");
                tamanhoPagina = arquivo.readInt();
                raiz = arquivo.readLong();
                primeiraFolha = arquivo.readLong();
                paginas = arquivo.readLong();
                quantidade = arquivo.readLong();
                livre = arquivo.readLong();
                pendente = arquivo.readLong() != 0;
                if (tamanhoPagina != 13 + 4 * (ordem - 1) + 8 * ordem
                        || paginas < 1 || paginas > (arquivo.length() - CABECALHO) / tamanhoPagina
                        || arquivo.length() != CABECALHO + paginas * tamanhoPagina
                        || quantidade < 0 || pendente) throw falha("Índice incompleto");
                conferirEndereco(raiz);
                conferirEndereco(primeiraFolha);
                if (livre != -1) conferirEndereco(livre);
            }
        } catch (IOException | RuntimeException e) {
            arquivo.close();
            throw e;
        }
    }

    private IOException falha(String mensagem) {
        return new IOException(mensagem + "; reconstrua o índice B+.");
    }

    private void pronto() throws IOException {
        if (pendente) throw falha("Operação anterior incompleta");
    }

    private void cabecalho() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(CABECALHO);
        b.putInt(MAGIC).putInt(VERSAO).putInt(ordem).putInt(tamanhoPagina);
        b.putLong(raiz).putLong(primeiraFolha).putLong(paginas).putLong(quantidade);
        b.putLong(livre).putLong(pendente ? 1 : 0);
        arquivo.seek(0);
        arquivo.write(b.array());
    }

    private void iniciar() throws IOException {
        pronto();
        pendente = true;
        cabecalho();
    }

    private void concluir() throws IOException {
        pendente = false;
        try { cabecalho(); }
        catch (IOException e) { pendente = true; throw e; }
    }

    private void conferirEndereco(long endereco) throws IOException {
        if (endereco < CABECALHO || (endereco - CABECALHO) % tamanhoPagina != 0
                || (endereco - CABECALHO) / tamanhoPagina >= paginas) {
            throw falha("Ponteiro de página inválido: " + endereco);
        }
    }

    private Pagina ler(long endereco) throws IOException {
        conferirEndereco(endereco);
        byte[] bytes = new byte[tamanhoPagina];
        arquivo.seek(endereco);
        arquivo.readFully(bytes);
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int tipo = b.get();
        if (tipo != 0 && tipo != 1) throw falha("Página não está em uso");
        Pagina p = new Pagina(endereco, tipo == 1);
        p.n = b.getInt();
        p.proxima = b.getLong();
        if (p.n < 0 || p.n >= ordem) throw falha("Quantidade de chaves inválida");
        for (int i = 0; i < ordem - 1; i++) p.chaves[i] = b.getInt();
        for (int i = 0; i < ordem; i++) p.ponteiros[i] = b.getLong();
        for (int i = 0; i < p.n; i++) {
            if (p.chaves[i] <= 0 || (i > 0 && p.chaves[i - 1] >= p.chaves[i])) {
                throw falha("Chaves inválidas ou fora de ordem");
            }
        }
        return p;
    }

    private void gravar(Pagina p) throws IOException {
        if (p.n < 0 || p.n >= ordem) throw falha("Página excede a capacidade");
        ByteBuffer b = ByteBuffer.allocate(tamanhoPagina);
        b.put((byte) (p.folha ? 1 : 0)).putInt(p.n).putLong(p.proxima);
        for (int i = 0; i < ordem - 1; i++) b.putInt(p.chaves[i]);
        for (int i = 0; i < ordem; i++) b.putLong(p.ponteiros[i]);
        arquivo.seek(p.endereco);
        arquivo.write(b.array());
    }

    private Pagina alocar(boolean folha) throws IOException {
        long endereco;
        if (livre == -1) {
            endereco = CABECALHO + paginas++ * tamanhoPagina;
        } else {
            endereco = livre;
            conferirEndereco(endereco);
            arquivo.seek(endereco);
            if (arquivo.readByte() != 2) throw falha("Lista de páginas livres inválida");
            livre = arquivo.readLong();
        }
        return new Pagina(endereco, folha);
    }

    private void liberar(long endereco) throws IOException {
        arquivo.seek(endereco);
        arquivo.writeByte(2);
        arquivo.writeLong(livre);
        livre = endereco;
    }

    private int filho(Pagina p, int id) {
        int i = 0;
        while (i < p.n && id >= p.chaves[i]) i++;
        return i;
    }

    private Pagina folha(int id) throws IOException {
        Pagina p = ler(raiz);
        Set<Long> visitadas = new HashSet<>();
        while (!p.folha) {
            if (!visitadas.add(p.endereco)) throw falha("Ciclo na árvore");
            p = ler(p.ponteiros[filho(p, id)]);
        }
        return p;
    }

    public long buscar(int id) throws IOException {
        pronto();
        Pagina p = folha(id);
        for (int i = 0; i < p.n; i++) if (p.chaves[i] == id) return p.ponteiros[i];
        return -1;
    }

    public void inserir(int id, long posicao) throws IOException {
        pronto();
        if (id <= 0 || posicao < 4) throw new IllegalArgumentException("ID/posição inválidos.");
        if (buscar(id) != -1) throw new IOException("ID duplicado no índice: " + id);
        iniciar();
        Divisao divisao = inserir(ler(raiz), id, posicao);
        if (divisao != null) {
            Pagina nova = alocar(false);
            nova.n = 1;
            nova.chaves[0] = divisao.separador;
            nova.ponteiros[0] = raiz;
            nova.ponteiros[1] = divisao.direita;
            gravar(nova);
            raiz = nova.endereco;
        }
        quantidade++;
        concluir();
    }

    private Divisao inserir(Pagina p, int id, long posicao) throws IOException {
        int i = 0;
        if (p.folha) {
            while (i < p.n && p.chaves[i] < id) i++;
            for (int j = p.n; j > i; j--) {
                p.chaves[j] = p.chaves[j - 1];
                p.ponteiros[j] = p.ponteiros[j - 1];
            }
            p.chaves[i] = id;
            p.ponteiros[i] = posicao;
            p.n++;
        } else {
            i = filho(p, id);
            Divisao d = inserir(ler(p.ponteiros[i]), id, posicao);
            if (d == null) return null;
            for (int j = p.n; j > i; j--) p.chaves[j] = p.chaves[j - 1];
            for (int j = p.n + 1; j > i + 1; j--) p.ponteiros[j] = p.ponteiros[j - 1];
            p.chaves[i] = d.separador;
            p.ponteiros[i + 1] = d.direita;
            p.n++;
        }
        if (p.n < ordem) { gravar(p); return null; }

        int meio = p.n / 2;
        Pagina direita = alocar(p.folha);
        int promovida = p.chaves[meio];
        if (p.folha) {
            // A chave copiada para o pai continua existindo na folha direita.
            direita.n = p.n - meio;
            System.arraycopy(p.chaves, meio, direita.chaves, 0, direita.n);
            System.arraycopy(p.ponteiros, meio, direita.ponteiros, 0, direita.n);
            direita.proxima = p.proxima;
            p.proxima = direita.endereco;
            splitsFolha++;
        } else {
            // Na página interna o separador promovido sai dos dois nós filhos.
            direita.n = p.n - meio - 1;
            System.arraycopy(p.chaves, meio + 1, direita.chaves, 0, direita.n);
            System.arraycopy(p.ponteiros, meio + 1, direita.ponteiros, 0, direita.n + 1);
            splitsInterno++;
        }
        p.n = meio;
        gravar(p);
        gravar(direita);
        return new Divisao(promovida, direita.endereco);
    }

    public boolean atualizarPosicao(int id, long posicao) throws IOException {
        pronto();
        if (posicao < 4) throw new IllegalArgumentException("Posição inválida.");
        Pagina p = folha(id);
        for (int i = 0; i < p.n; i++) {
            if (p.chaves[i] == id) {
                iniciar();
                p.ponteiros[i] = posicao;
                gravar(p);
                concluir();
                return true;
            }
        }
        return false;
    }

    public boolean remover(int id) throws IOException {
        if (buscar(id) == -1) return false;
        iniciar();
        remover(ler(raiz), id);
        Pagina p = ler(raiz);
        if (!p.folha && p.n == 0) {
            raiz = p.ponteiros[0];
            liberar(p.endereco);
        }
        quantidade--;
        concluir();
        return true;
    }

    private int minimo(Pagina p) { return p.folha ? ordem / 2 : (ordem - 1) / 2; }

    private void remover(Pagina p, int id) throws IOException {
        if (p.folha) {
            int i = 0;
            while (i < p.n && p.chaves[i] != id) i++;
            for (int j = i; j < p.n - 1; j++) {
                p.chaves[j] = p.chaves[j + 1];
                p.ponteiros[j] = p.ponteiros[j + 1];
            }
            p.n--;
            gravar(p);
            return;
        }
        int i = filho(p, id);
        remover(ler(p.ponteiros[i]), id);
        Pagina alvo = ler(p.ponteiros[i]);
        if (alvo.n < minimo(alvo)) equilibrar(p, i, alvo);
        atualizarSeparadores(p);
        gravar(p);
    }

    /** Separador i é sempre o menor ID da subárvore i+1. */
    private void atualizarSeparadores(Pagina p) throws IOException {
        if (p.folha) return;
        for (int i = 0; i < p.n; i++) {
            Pagina menor = ler(p.ponteiros[i + 1]);
            Set<Long> vistos = new HashSet<>();
            while (!menor.folha) {
                if (!vistos.add(menor.endereco)) throw falha("Ciclo nos filhos");
                menor = ler(menor.ponteiros[0]);
            }
            if (menor.n == 0) throw falha("Subárvore vazia");
            p.chaves[i] = menor.chaves[0];
        }
    }

    private void equilibrar(Pagina pai, int i, Pagina alvo) throws IOException {
        Pagina esquerda = i > 0 ? ler(pai.ponteiros[i - 1]) : null;
        Pagina direita = i < pai.n ? ler(pai.ponteiros[i + 1]) : null;
        if (esquerda != null && esquerda.n > minimo(esquerda)) {
            if (alvo.folha) {
                for (int j = alvo.n; j > 0; j--) {
                    alvo.chaves[j] = alvo.chaves[j - 1];
                    alvo.ponteiros[j] = alvo.ponteiros[j - 1];
                }
                alvo.chaves[0] = esquerda.chaves[esquerda.n - 1];
                alvo.ponteiros[0] = esquerda.ponteiros[esquerda.n - 1];
            } else {
                for (int j = alvo.n + 1; j > 0; j--) alvo.ponteiros[j] = alvo.ponteiros[j - 1];
                alvo.ponteiros[0] = esquerda.ponteiros[esquerda.n];
            }
            esquerda.n--;
            alvo.n++;
            atualizarSeparadores(esquerda);
            atualizarSeparadores(alvo);
            gravar(esquerda);
            gravar(alvo);
            redistribuicoes++;
        } else if (direita != null && direita.n > minimo(direita)) {
            if (alvo.folha) {
                alvo.chaves[alvo.n] = direita.chaves[0];
                alvo.ponteiros[alvo.n] = direita.ponteiros[0];
                for (int j = 0; j < direita.n - 1; j++) {
                    direita.chaves[j] = direita.chaves[j + 1];
                    direita.ponteiros[j] = direita.ponteiros[j + 1];
                }
            } else {
                alvo.ponteiros[alvo.n + 1] = direita.ponteiros[0];
                for (int j = 0; j < direita.n; j++) direita.ponteiros[j] = direita.ponteiros[j + 1];
            }
            alvo.n++;
            direita.n--;
            atualizarSeparadores(alvo);
            atualizarSeparadores(direita);
            gravar(alvo);
            gravar(direita);
            redistribuicoes++;
        } else {
            // Fusão sempre preserva a página esquerda e sua posição no encadeamento.
            Pagina a = esquerda != null ? esquerda : alvo;
            Pagina b = esquerda != null ? alvo : direita;
            if (b == null) throw falha("Página sem irmão para fusão");
            int removerFilho = esquerda != null ? i : i + 1;
            if (a.folha) {
                System.arraycopy(b.chaves, 0, a.chaves, a.n, b.n);
                System.arraycopy(b.ponteiros, 0, a.ponteiros, a.n, b.n);
                a.n += b.n;
                a.proxima = b.proxima;
            } else {
                System.arraycopy(b.ponteiros, 0, a.ponteiros, a.n + 1, b.n + 1);
                a.n += b.n + 1;
                atualizarSeparadores(a);
            }
            gravar(a);
            liberar(b.endereco);
            for (int j = removerFilho; j < pai.n; j++) pai.ponteiros[j] = pai.ponteiros[j + 1];
            pai.n--;
            fusoes++;
        }
    }

    @FunctionalInterface
    public interface Visitante { void visitar(int id, long posicao) throws IOException; }

    public void percorrerFolhas(Visitante visitante) throws IOException {
        pronto();
        long endereco = primeiraFolha;
        Set<Long> vistos = new HashSet<>();
        int anterior = 0;
        long total = 0;
        while (endereco != -1) {
            if (!vistos.add(endereco)) throw falha("Ciclo nas folhas");
            Pagina p = ler(endereco);
            if (!p.folha) throw falha("Encadeamento aponta para página interna");
            for (int i = 0; i < p.n; i++) {
                if (p.chaves[i] <= anterior) throw falha("ID duplicado ou folhas fora de ordem");
                anterior = p.chaves[i];
                visitante.visitar(anterior, p.ponteiros[i]);
                total++;
            }
            endereco = p.proxima;
        }
        if (total != quantidade) throw falha("Contagem de entradas divergente");
    }

    /** O visitante opcional valida id/posição contra o arquivo de dados. */
    public String validar(Visitante conferirDado) throws IOException {
        pronto();
        Set<Long> vistos = new HashSet<>();
        List<Long> folhas = new ArrayList<>();
        int[] profundidadeFolha = {-1};
        long[] total = {0};
        validarNo(raiz, true, 0, 1L, (long) Integer.MAX_VALUE + 1,
                vistos, folhas, profundidadeFolha, total, conferirDado);
        if (folhas.get(0) != primeiraFolha || total[0] != quantidade) throw falha("Metadados divergentes");
        for (int i = 0; i < folhas.size(); i++) {
            long esperada = i + 1 < folhas.size() ? folhas.get(i + 1) : -1;
            if (ler(folhas.get(i)).proxima != esperada) throw falha("Encadeamento de folhas divergente/cíclico");
        }
        long atual = livre;
        while (atual != -1) {
            conferirEndereco(atual);
            if (!vistos.add(atual)) throw falha("Página livre repetida ou em uso");
            arquivo.seek(atual);
            if (arquivo.readByte() != 2) throw falha("Página livre inválida");
            atual = arquivo.readLong();
        }
        if (vistos.size() != paginas) throw falha("Páginas órfãs");
        return "B+: OK; entradas=" + quantidade + "; altura=" + (profundidadeFolha[0] + 1)
                + "; folhas=" + folhas.size() + "; páginas=" + paginas;
    }

    private int validarNo(long endereco, boolean ehRaiz, int nivel, long inferior, long superior,
                          Set<Long> vistos, List<Long> folhas, int[] nivelFolha, long[] total,
                          Visitante conferirDado) throws IOException {
        if (nivel > 64 || !vistos.add(endereco)) throw falha("Ciclo ou profundidade inválida");
        Pagina p = ler(endereco);
        if ((!ehRaiz && p.n < minimo(p)) || (ehRaiz && !p.folha && p.n == 0)) {
            throw falha("Ocupação mínima inválida");
        }
        for (int i = 0; i < p.n; i++) {
            if (p.chaves[i] < inferior || p.chaves[i] >= superior) throw falha("Chave fora da subárvore");
        }
        if (p.folha) {
            if (nivelFolha[0] == -1) nivelFolha[0] = nivel;
            if (nivelFolha[0] != nivel) throw falha("Folhas em profundidades diferentes");
            folhas.add(endereco);
            total[0] += p.n;
            for (int i = 0; i < p.n; i++) {
                if (p.ponteiros[i] < 4) throw falha("Posição de registro inválida");
                if (conferirDado != null) conferirDado.visitar(p.chaves[i], p.ponteiros[i]);
            }
            return p.n == 0 ? 0 : p.chaves[0];
        }
        if (p.proxima != -1) throw falha("Página interna com elo de folha");
        int menor = 0;
        for (int i = 0; i <= p.n; i++) {
            int min = validarNo(p.ponteiros[i], false, nivel + 1,
                    i == 0 ? inferior : p.chaves[i - 1], i == p.n ? superior : p.chaves[i],
                    vistos, folhas, nivelFolha, total, conferirDado);
            if (i == 0) menor = min;
            else if (min != p.chaves[i - 1]) throw falha("Separador incoerente");
        }
        return menor;
    }

    public int getOrdem() { return ordem; }
    public long getQuantidade() { return quantidade; }
    public int getTamanhoPagina() { return tamanhoPagina; }
    public int getSplitsFolha() { return splitsFolha; }
    public int getSplitsInterno() { return splitsInterno; }
    public int getRedistribuicoes() { return redistribuicoes; }
    public int getFusoes() { return fusoes; }

    @Override public void close() throws IOException {
        try { arquivo.getFD().sync(); } finally { arquivo.close(); }
    }

    private final class Pagina {
        final long endereco;
        final boolean folha;
        int n;
        long proxima = -1;
        // Uma posição extra acomoda o overflow apenas durante o split em memória.
        final int[] chaves = new int[ordem];
        final long[] ponteiros = new long[ordem + 1];
        Pagina(long endereco, boolean folha) { this.endereco = endereco; this.folha = folha; }
    }

    private static final class Divisao {
        final int separador;
        final long direita;
        Divisao(int separador, long direita) { this.separador = separador; this.direita = direita; }
    }
}
