package service;

import dao.ArquivoSequencial;
import index.bplus.ArvoreBPlus;
import model.Carro;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** Coordena a B+ com o formato do TP1. Uso por uma aplicação/escritor de cada vez.
 * O marcador persistente evita usar índices após uma operação interrompida.
 * Não há rollback dos dados: uma falha exige reconstruir a partir da base ativa.
 */
public class GerenciadorIndices {
    private final ArquivoSequencial dados;
    private final Path indice, marca, assinatura;

    public GerenciadorIndices(ArquivoSequencial dados, Path indice) {
        this.dados = dados;
        this.indice = indice;
        marca = indice.resolveSibling(indice.getFileName() + ".pendente");
        assinatura = indice.resolveSibling(indice.getFileName() + ".meta");
        if (dados.getCaminho().toAbsolutePath().normalize().equals(indice.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Dados e índice devem usar arquivos distintos.");
        }
    }

    public boolean existe() { return Files.exists(indice); }
    public boolean ativo() { return existe() || Files.exists(marca); }

    public int ordem() throws IOException {
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) { return arvore.getOrdem(); }
    }

    private IOException inconsistente() {
        return new IOException("Índice ausente/desatualizado ou operação interrompida. Reconstrua a B+.");
    }

    private String estadoDados() throws IOException {
        BasicFileAttributes a = Files.readAttributes(dados.getCaminho(), BasicFileAttributes.class);
        return dados.getCaminho().toRealPath() + "|" + a.fileKey() + "|" + a.size()
                + "|" + a.lastModifiedTime();
    }

    private void conferir() throws IOException {
        if (!existe() || Files.exists(marca) || !Files.isRegularFile(assinatura)) throw inconsistente();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(assinatura))) {
            if (!in.readUTF().equals(estadoDados())) throw inconsistente();
        }
    }

    private void marcar(int ordem) throws IOException {
        if (indice.getParent() != null) Files.createDirectories(indice.getParent());
        try (RandomAccessFile arquivo = new RandomAccessFile(marca.toFile(), "rw")) {
            arquivo.setLength(0);
            arquivo.writeInt(ordem);
            arquivo.getFD().sync();
        }
    }

    private static void substituir(Path fonte, Path destino) throws IOException {
        try {
            Files.move(fonte, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(fonte, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void concluir() throws IOException {
        Path temp = Files.createTempFile(indice.toAbsolutePath().getParent(), "assinatura-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
                out.writeUTF(estadoDados());
            }
            try (RandomAccessFile f = new RandomAccessFile(temp.toFile(), "rw")) { f.getFD().sync(); }
            substituir(temp, assinatura);
            Files.deleteIfExists(marca);
        } finally { Files.deleteIfExists(temp); }
    }

    public void reconstruir(int ordem) throws IOException {
        ArvoreBPlus.validarOrdem(ordem);
        dados.garantirArquivo();
        String antes = estadoDados();
        marcar(ordem);
        Path temp = Files.createTempFile(indice.toAbsolutePath().getParent(), "bplus-", ".tmp");
        Files.delete(temp); // criar() exige destino novo; é exclusivamente o temporário desta operação.
        try {
            try (ArvoreBPlus arvore = ArvoreBPlus.criar(temp, ordem)) {
                dados.percorrerAtivos(arvore::inserir);
                arvore.validar(null);
            }
            if (!antes.equals(estadoDados())) throw new IOException("Dados alterados durante reconstrução.");
            substituir(temp, indice);
            concluir();
        } finally { Files.deleteIfExists(temp); }
    }

    public Carro buscar(int id) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            long posicao = arvore.buscar(id);
            return posicao == -1 ? null : dados.readAtPosition(posicao, id);
        }
    }

    public int criar(Carro carro) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            marcar(arvore.getOrdem());
            ArquivoSequencial.InfoRegistro registro = dados.createComPosicao(carro);
            arvore.inserir(registro.id, registro.posicao);
        }
        concluir();
        return carro.getId();
    }

    public boolean atualizar(Carro carro) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            long anterior = arvore.buscar(carro.getId());
            if (anterior == -1) return false;
            dados.readAtPosition(anterior, carro.getId());
            marcar(arvore.getOrdem());
            long nova = dados.updateAtPosition(anterior, carro);
            if (!arvore.atualizarPosicao(carro.getId(), nova)) throw inconsistente();
        }
        concluir();
        return true;
    }

    public boolean excluir(int id) throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            long posicao = arvore.buscar(id);
            if (posicao == -1) return false;
            dados.readAtPosition(posicao, id);
            marcar(arvore.getOrdem());
            dados.deleteAtPosition(posicao, id);
            if (!arvore.remover(id)) throw inconsistente();
        }
        concluir();
        return true;
    }

    @FunctionalInterface
    public interface Operacao<T> { T executar() throws IOException; }

    /** Preserva o CRUD sequencial/carga do menu e recompõe a B+ se estiver ativa.
     * Também protege ordenações: o marcador é gravado ANTES de mudar os endereços.
     */
    public <T> T executarAlteracaoSequencial(Operacao<T> operacao) throws IOException {
        if (!ativo()) return operacao.executar();
        conferir();
        int ordem = ordem();
        marcar(ordem);
        T resultado = operacao.executar();
        reconstruir(ordem);
        return resultado;
    }

    public OrdenacaoExterna.ResultadoOrdenacao ordenar(Path temporarios, int caminhos,
                                                       int memoria, boolean selecao) throws IOException {
        return executarAlteracaoSequencial(() -> selecao
                ? new OrdenacaoExterna().ordenarComSelecaoPorSubstituicao(
                        dados.getCaminho(), temporarios, caminhos, memoria)
                : new OrdenacaoExterna().ordenar(dados.getCaminho(), temporarios, caminhos, memoria));
    }

    public String validar() throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            String resultado = arvore.validar((id, pos) -> dados.readAtPosition(pos, id));
            long[] total = {0};
            dados.percorrerAtivos((id, pos) -> {
                if (arvore.buscar(id) != pos) throw new IOException("Entrada ausente/divergente para ID " + id);
                total[0]++;
            });
            if (total[0] != arvore.getQuantidade()) throw inconsistente();
            return resultado + "; dados ativos=" + total[0];
        }
    }

    public String informacoes() throws IOException {
        conferir();
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            return "Ordem=" + arvore.getOrdem() + "; entradas=" + arvore.getQuantidade()
                    + "; página=" + arvore.getTamanhoPagina() + " bytes; arquivo=" + Files.size(indice) + " bytes";
        }
    }

    /** Medição simples da mesma sequência de consultas; não é benchmark científico. */
    public String compararBuscas(int[] ids, int repeticoes) throws IOException {
        if (ids.length == 0 || repeticoes < 1 || repeticoes > 1000) {
            throw new IllegalArgumentException("Informe IDs e de 1 a 1000 repetições.");
        }
        conferir();
        long sequencial = 0, indexado = 0;
        try (ArvoreBPlus arvore = ArvoreBPlus.abrir(indice)) {
            for (int r = 0; r < repeticoes; r++) {
                for (int id : ids) {
                    long inicio = System.nanoTime();
                    Carro a = dados.read(id);
                    sequencial += System.nanoTime() - inicio;
                    inicio = System.nanoTime();
                    long pos = arvore.buscar(id);
                    Carro b = pos == -1 ? null : dados.readAtPosition(pos, id);
                    indexado += System.nanoTime() - inicio;
                    if (a == null ? b != null : !a.equals(b)) throw inconsistente();
                }
            }
        }
        return "IDs=" + Arrays.toString(ids) + "; consultas=" + (long) ids.length * repeticoes
                + "; sequencial_ns=" + sequencial + "; bplus_ns=" + indexado;
    }
}
