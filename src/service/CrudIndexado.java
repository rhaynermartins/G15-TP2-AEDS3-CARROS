package service;

import dao.ArquivoSequencial;
import index.lista.ListaInvertida;
import model.Carro;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A seleção conserva a origem e o endereço, sem trocar de índice antes da mutação. */
public final class CrudIndexado {
    public enum Metodo { BPLUS, HASH, LISTA }
    public static final int TAMANHO_PAGINA = 20;
    private final GerenciadorIndices indices;

    public CrudIndexado(GerenciadorIndices indices) { this.indices = indices; }

    public ArquivoSequencial.InfoRegistro criar(Carro carro) throws IOException {
        indices.estadoConsistente();
        return indices.criarComPosicao(carro);
    }

    public RegistroLocalizado localizar(Metodo metodo, int id) throws IOException {
        if (metodo != Metodo.BPLUS && metodo != Metodo.HASH) {
            throw new IllegalArgumentException("Para listas, execute uma consulta e selecione um de seus resultados.");
        }
        String estado = indices.estadoConsistente();
        long pos = indices.localizarPosicao(id, metodo == Metodo.HASH);
        return pos == -1 ? null : new RegistroLocalizado(metodo, estado, pos, indices.lerDireto(pos, id));
    }

    public Consulta consultar(Integer ano, String caracteristica) throws IOException {
        String estado = indices.estadoConsistente();
        return new Consulta(estado, ano, caracteristica, indices.postings(ano, caracteristica));
    }

    public long atualizar(RegistroLocalizado registro, Carro novo) throws IOException {
        conferir(registro);
        if (novo.getId() != registro.id) throw new IllegalArgumentException("O ID da seleção não pode mudar.");
        return indices.atualizarNaPosicao(registro.posicao, novo);
    }

    public void excluir(RegistroLocalizado registro) throws IOException {
        conferir(registro);
        indices.excluirNaPosicao(registro.posicao, registro.id);
    }

    private void conferir(RegistroLocalizado registro) throws IOException {
        if (registro == null || registro.dono != this) throw new IllegalArgumentException("Seleção inválida para este serviço.");
        conferirEstado(registro.estado);
    }

    private void conferirEstado(String estado) throws IOException {
        if (!estado.equals(indices.estadoConsistente())) {
            throw new IOException("Os dados mudaram desde a pesquisa. Consulte e selecione novamente.");
        }
    }

    public final class RegistroLocalizado {
        private final CrudIndexado dono = CrudIndexado.this;
        private final Metodo metodo;
        private final String estado;
        private final long posicao;
        private final int id;
        private final Carro carro;

        private RegistroLocalizado(Metodo metodo, String estado, long posicao, Carro carro) {
            this.metodo = metodo; this.estado = estado; this.posicao = posicao;
            this.carro = carro; this.id = carro.getId();
        }
        public Metodo getMetodo() { return metodo; }
        public long getPosicao() { return posicao; }
        public int getId() { return id; }
        public Carro getCarro() {
            return new Carro(id, carro.getCodigo(), carro.getNome(), carro.getDataRegistro(), carro.getCaracteristicas(), carro.getAno());
        }
    }

    /** Guarda só postings; cada página materializa no máximo 20 carros por seek. */
    public final class Consulta {
        private final String estado;
        private final Integer ano;
        private final String caracteristica;
        private final List<ListaInvertida.Posting> postings;

        private Consulta(String estado, Integer ano, String caracteristica, List<ListaInvertida.Posting> postings) {
            this.estado = estado; this.ano = ano; this.caracteristica = caracteristica; this.postings = postings;
        }
        public int total() { return postings.size(); }

        public List<Carro> pagina(int inicio) throws IOException {
            conferirEstado(estado);
            if (inicio < 0 || inicio > total()) throw new IllegalArgumentException("Página fora dos resultados.");
            List<Carro> carros = new ArrayList<>();
            for (int i = inicio; i < Math.min((long) inicio + TAMANHO_PAGINA, total()); i++) {
                carros.add(indices.lerPosting(postings.get(i), ano, caracteristica));
            }
            return carros;
        }

        public RegistroLocalizado selecionar(int id) throws IOException {
            conferirEstado(estado);
            int esquerda = 0, direita = total() - 1;
            while (esquerda <= direita) {
                int meio = (esquerda + direita) >>> 1;
                ListaInvertida.Posting p = postings.get(meio);
                if (id < p.id) direita = meio - 1;
                else if (id > p.id) esquerda = meio + 1;
                else return new RegistroLocalizado(Metodo.LISTA, estado, p.posicao,
                            indices.lerPosting(p, ano, caracteristica));
            }
            throw new IllegalArgumentException("O ID não pertence aos resultados desta pesquisa.");
        }
    }
}
