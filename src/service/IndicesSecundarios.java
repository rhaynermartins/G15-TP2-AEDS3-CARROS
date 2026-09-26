package service;

import dao.ArquivoSequencial;
import index.hash.HashEstendido;
import index.lista.ListaInvertida;
import model.Carro;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Hash e duas listas, coordenados pelo marcador comum de GerenciadorIndices.
 * Escritor único. A configuração guarda a população INICIAL, mesmo após Delete.
 */
final class IndicesSecundarios {
    private static final int MAGIC = 0x46533249;
    private final ArquivoSequencial dados;
    private final Path pasta, configuracao, diretorio, buckets, anos, caracteristicas;

    IndicesSecundarios(ArquivoSequencial dados, Path pasta) {
        this.dados = dados;
        this.pasta = pasta.toAbsolutePath();
        configuracao = this.pasta.resolve("fase2.meta");
        diretorio = this.pasta.resolve("hash_diretorio.idx");
        buckets = this.pasta.resolve("hash_buckets.idx");
        anos = this.pasta.resolve("lista_ano.idx");
        caracteristicas = this.pasta.resolve("lista_caracteristicas.idx");
    }

    boolean ativo() {
        return Files.exists(configuracao) || Files.exists(diretorio) || Files.exists(buckets)
                || Files.exists(anos) || Files.exists(caracteristicas);
    }

    void configurar(long inicial) throws IOException {
        HashEstendido.capacidadePara(inicial);
        if (Files.exists(configuracao)) {
            if (quantidadeInicial() != inicial) throw new IOException("A quantidade inicial persistida não pode mudar.");
            return;
        }
        Files.createDirectories(pasta);
        Path temp = Files.createTempFile(pasta, "configuracao-", ".tmp");
        try {
            try (RandomAccessFile f = new RandomAccessFile(temp.toFile(), "rw")) {
                f.writeInt(MAGIC); f.writeInt(1); f.writeLong(inicial); f.getFD().sync();
            }
            substituir(temp, configuracao);
        } finally { Files.deleteIfExists(temp); }
    }

    long quantidadeInicial() throws IOException {
        if (!Files.isRegularFile(configuracao)) throw new IOException("Configuração inicial ausente; reconstrua todos os índices informando a população inicial.");
        try (RandomAccessFile f = new RandomAccessFile(configuracao.toFile(), "r")) {
            if (f.length() != 16 || f.readInt() != MAGIC || f.readInt() != 1) throw new IOException("Configuração inicial incompatível.");
            long inicial = f.readLong();
            if (inicial < 1 || inicial > 20_000_000) throw new IOException("População inicial inválida.");
            return inicial;
        }
    }

    void conferirArquivos() throws IOException {
        quantidadeInicial();
        for (Path p : new Path[]{diretorio, buckets, anos, caracteristicas}) {
            if (!Files.isRegularFile(p)) throw new IOException("Índice ausente: " + p + "; reconstrua todos os índices.");
        }
    }

    HashEstendido abrirHash() throws IOException {
        long inicial = quantidadeInicial();
        HashEstendido hash = HashEstendido.abrir(diretorio, buckets);
        if (hash.getQuantidadeInicial() != inicial) {
            hash.close(); throw new IOException("Hash e configuração inicial divergentes.");
        }
        return hash;
    }

    private ListaInvertida abrirLista(boolean porAno) throws IOException {
        ListaInvertida lista = ListaInvertida.abrir(porAno ? anos : caracteristicas);
        if (lista.getTipo() != (porAno ? ListaInvertida.ANO : ListaInvertida.CARACTERISTICA)) {
            lista.close(); throw new IOException("Tipo de lista incompatível com o arquivo.");
        }
        return lista;
    }

    static Set<String> termos(Carro carro) {
        Set<String> resultado = new LinkedHashSet<>();
        for (String valor : carro.getCaracteristicas()) {
            String termo = ListaInvertida.normalizar(valor);
            if (!termo.isEmpty()) resultado.add(termo);
        }
        return resultado;
    }

    private static void substituir(Path origem, Path destino) throws IOException {
        try { Files.move(origem, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Fluxo de um registro por vez; nenhum Carro completo permanece no índice. */
    void reconstruir() throws IOException {
        long inicial = quantidadeInicial();
        Path temp = Files.createTempDirectory(pasta, "fase2-");
        Path d = temp.resolve(diretorio.getFileName()), b = temp.resolve(buckets.getFileName());
        Path a = temp.resolve(anos.getFileName()), c = temp.resolve(caracteristicas.getFileName());
        try {
            try (HashEstendido hash = HashEstendido.criar(d, b, inicial);
                 ListaInvertida ano = ListaInvertida.criar(a, ListaInvertida.ANO);
                 ListaInvertida caracteristica = ListaInvertida.criar(c, ListaInvertida.CARACTERISTICA)) {
                dados.percorrerAtivos((id, pos) -> {
                    Carro carro = dados.readAtPosition(pos, id);
                    hash.inserir(id, pos);
                    ano.inserir(carro.getAno(), id, pos);
                    for (String termo : termos(carro)) caracteristica.inserir(termo, id, pos);
                });
                hash.validar(null); ano.validar(null); caracteristica.validar(null);
            }
            // O marcador global só é retirado depois de TODOS os arquivos publicados.
            substituir(d, diretorio); substituir(b, buckets);
            substituir(a, anos); substituir(c, caracteristicas);
        } finally {
            for (Path p : new Path[]{d, b, a, c}) Files.deleteIfExists(p);
            Files.deleteIfExists(temp);
        }
    }

    void inserir(Carro carro, long pos) throws IOException {
        try (HashEstendido h = abrirHash(); ListaInvertida a = abrirLista(true); ListaInvertida c = abrirLista(false)) {
            h.inserir(carro.getId(), pos); a.inserir(carro.getAno(), carro.getId(), pos);
            for (String termo : termos(carro)) c.inserir(termo, carro.getId(), pos);
        }
    }

    private void exigir(boolean presente) throws IOException {
        if (!presente) throw new IOException("Entrada esperada ausente; reconstrua todos os índices.");
    }

    void atualizar(Carro antigo, Carro novo, long pos) throws IOException {
        int id = novo.getId();
        Set<String> antes = termos(antigo), depois = termos(novo);
        try (HashEstendido h = abrirHash(); ListaInvertida a = abrirLista(true); ListaInvertida c = abrirLista(false)) {
            exigir(h.atualizarPosicao(id, pos));
            if (antigo.getAno() == novo.getAno()) exigir(a.atualizarPosicao(novo.getAno(), id, pos));
            else { exigir(a.remover(antigo.getAno(), id)); a.inserir(novo.getAno(), id, pos); }
            for (String termo : antes) {
                if (depois.contains(termo)) exigir(c.atualizarPosicao(termo, id, pos));
                else exigir(c.remover(termo, id));
            }
            for (String termo : depois) if (!antes.contains(termo)) c.inserir(termo, id, pos);
        }
    }

    void remover(Carro antigo) throws IOException {
        int id = antigo.getId();
        try (HashEstendido h = abrirHash(); ListaInvertida a = abrirLista(true); ListaInvertida c = abrirLista(false)) {
            exigir(h.remover(id)); exigir(a.remover(antigo.getAno(), id));
            for (String termo : termos(antigo)) exigir(c.remover(termo, id));
        }
    }

    Carro buscarHash(int id) throws IOException {
        try (HashEstendido h = abrirHash()) {
            long pos = h.buscar(id);
            return pos == -1 ? null : dados.readAtPosition(pos, id);
        }
    }

    List<ListaInvertida.Posting> postings(Integer ano, String caracteristica) throws IOException {
        if (ano == null && caracteristica == null) throw new IllegalArgumentException("Informe pelo menos um filtro.");
        String termo = caracteristica == null ? null : ListaInvertida.normalizar(caracteristica);
        List<ListaInvertida.Posting> postings;
        if (ano != null && termo != null) {
            try (ListaInvertida a = abrirLista(true); ListaInvertida c = abrirLista(false)) {
                postings = ListaInvertida.intersecao(a.buscar(ano), c.buscar(termo));
            }
        } else {
            try (ListaInvertida lista = abrirLista(ano != null)) { postings = lista.buscar(ano != null ? ano : termo); }
        }
        return postings;
    }

    Carro lerPosting(ListaInvertida.Posting posting, Integer ano, String caracteristica) throws IOException {
        Carro carro = dados.readAtPosition(posting.posicao, posting.id);
        if ((ano != null && carro.getAno() != ano)
                || (caracteristica != null && !termos(carro).contains(ListaInvertida.normalizar(caracteristica)))) {
            throw new IOException("Posting incompatível com os dados; reconstrua os índices.");
        }
        return carro;
    }

    List<Carro> buscar(Integer ano, String caracteristica) throws IOException {
        List<Carro> resultado = new ArrayList<>();
        for (ListaInvertida.Posting p : postings(ano, caracteristica)) resultado.add(lerPosting(p, ano, caracteristica));
        return resultado;
    }

    String informacoesListas() throws IOException {
        try (ListaInvertida a = abrirLista(true); ListaInvertida c = abrirLista(false)) {
            return "Lista Ano: termos=" + a.getQuantidadeTermos() + "; postings=" + a.getQuantidadePostings()
                    + "; arquivo=" + Files.size(anos) + " bytes\nLista Características: termos="
                    + c.getQuantidadeTermos() + "; postings=" + c.getQuantidadePostings()
                    + "; arquivo=" + Files.size(caracteristicas) + " bytes";
        }
    }

    String informacoesHash() throws IOException {
        try (HashEstendido h = abrirHash()) {
            return h.informacoes() + "; diretório=" + Files.size(diretorio) + " bytes; buckets=" + Files.size(buckets) + " bytes";
        }
    }

    String validarHash() throws IOException {
        try (HashEstendido h = abrirHash()) {
            String resultado = h.validar((id, pos) -> dados.readAtPosition(pos, id));
            long[] total = {0}; dados.percorrerAtivos((id, pos) -> total[0]++);
            exigir(total[0] == h.getQuantidade());
            return resultado + "; dados ativos=" + total[0];
        }
    }

    String validarLista(boolean porAno) throws IOException {
        try (ListaInvertida lista = abrirLista(porAno)) {
            String resultado = lista.validar((termo, id, pos) -> {
                Carro carro = dados.readAtPosition(pos, id);
                exigir(porAno ? termo.equals(carro.getAno()) : termos(carro).contains(termo));
            });
            long[] esperado = {0};
            dados.percorrerAtivos((id, pos) -> esperado[0] += porAno ? 1 : termos(dados.readAtPosition(pos, id)).size());
            // Unicidade por termo + pertinência + cardinalidade garantem completude.
            exigir(esperado[0] == lista.getQuantidadePostings());
            return resultado + "; arquivo=" + Files.size(porAno ? anos : caracteristicas) + " bytes";
        }
    }
}
