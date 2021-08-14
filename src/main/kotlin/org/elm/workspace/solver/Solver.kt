package org.elm.workspace.solver

import org.elm.workspace.Constraint
import org.elm.workspace.Version
import org.elm.workspace.solver.SolverResult.DeadEnd
import org.elm.workspace.solver.SolverResult.Proceed

typealias PkgName = String

interface Pkg {
    val name: PkgName
    val version: Version
    val elmConstraint: Constraint
    val dependencies: Map<PkgName, Constraint>
}

fun Version.satisfies(constraint: Constraint): Boolean = constraint.contains(this)

interface Repository {
    val elmCompilerVersion: Version
    operator fun get(name: PkgName): List<Pkg>
}

/**
 * Attempt to find a solution for the constraints given by [deps] using the Elm packages
 * that we know about in [repo].
 */
fun solve(deps: Map<PkgName, Constraint>, repo: Repository): Map<PkgName, Version>? {
    val solutions = mapOf<PkgName, Version>()
    return when (val res = Solver(repo).solve(deps, solutions)) {
        DeadEnd -> null
        is Proceed -> res.solutions
    }
}

private sealed class SolverResult {
    object DeadEnd : SolverResult()
    data class Proceed(
            val pending: Map<PkgName, Constraint>,
            val solutions: Map<PkgName, Version>
    ) : SolverResult()
}

private class Solver(private val repo: Repository) {
    fun solve(deps: Map<PkgName, Constraint>, solutions: Map<PkgName, Version>): SolverResult {
        // Pick a dep to solve
        val (dep, restDeps) = deps.pick()
        if (dep == null) return Proceed(deps, solutions)

        // Figure out which versions of the dep actually exist
        var candidates = repo[dep.name]
                .filter { it.version.satisfies(dep.constraint) }
                .sortedByDescending { it.version }

        // Further restrict the candidates if a pending solution has already bound the version of this dep.
        val solvedVersion = solutions[dep.name]
        if (solvedVersion != null)
            candidates = candidates.filter { it.version == solvedVersion }

        // Speculatively try each candidate version to see if it is a partial solution
        loop@ for (candidate in candidates) {
            if (repo.elmCompilerVersion !in candidate.elmConstraint) continue@loop
            val tentativeDeps = restDeps.combine(candidate.dependencies) ?: continue@loop
            val tentativeSolutions = solutions + (dep.name to candidate.version)

            when (val res = solve(tentativeDeps, tentativeSolutions)) {
                DeadEnd -> continue@loop
                is Proceed -> return solve(res.pending, res.solutions)
            }
        }

        return DeadEnd
    }
}


private data class Dep(val name: PkgName, val constraint: Constraint)

private fun Map<PkgName, Constraint>.pick(): Pair<Dep?, Map<PkgName, Constraint>> {
    // Pick a dep/constraint (Elm compiler picks by pkg name in lexicographically ascending order)
    val dep = minByOrNull { it.key } ?: return (null to this)
    return Dep(dep.key, dep.value) to minus(dep.key)
}

private fun Map<PkgName, Constraint>.combine(other: Map<PkgName, Constraint>): Map<PkgName, Constraint>? =
        keys.union(other.keys)
                .associateWith { key ->
                    val v1 = this[key]
                    val v2 = other[key]
                    when {
                        v1 != null && v2 != null -> {
                            v1.intersect(v2) ?: return null
                        }
                        v1 != null -> v1
                        v2 != null -> v2
                        else -> error("impossible")
                    }
                }
