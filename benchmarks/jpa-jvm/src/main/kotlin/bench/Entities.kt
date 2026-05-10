package bench

import jakarta.persistence.*

/** Plain DTO for JPQL constructor-expression projections. Not a JPA entity. */
class ChildParentDto(val cid: Int, val value: Double, val name: String)

@Entity
@Table(name = "bench_warmup")
class BenchWarmup {
    @Id var id: Int = 0
    @Column(nullable = false, length = 50) var payload: String = ""
}

@Entity
@Table(name = "bench_parent")
class BenchParent {
    @Id var id: Int = 0
    @Column(nullable = false, length = 100) var name: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Long = 0L
}

@Entity
@Table(name = "bench_child")
class BenchChild {
    @Id var id: Int = 0

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = false)
    lateinit var parent: BenchParent

    @Column(nullable = false, length = 20) var status: String = ""
    @Column(nullable = false) var value: Double = 0.0
    @Column(nullable = false, length = 200) var payload: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Long = 0L
}
