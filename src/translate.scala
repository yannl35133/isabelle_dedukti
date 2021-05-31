/** Translation of Isabelle (proof)terms into lambda-Pi calculus **/


package isabelle.dedukti

import isabelle.Export_Theory.{No_Syntax, Prefix}
import isabelle._
import lambdapi._

import scala.annotation.tailrec


// preludes for minimal Higher-order Logic (Isabelle/Pure)
// see https://raw.githubusercontent.com/Deducteam/Libraries/master/theories/stt.dk

object Prelude
{
  var namesSet: Set[String] = Set()
  var namesMap: Map[String, String] = Map()

  def full_name(a: String, kind: String): String =
    a.replace(".", "__") + "__" + kind

  def names_add(id: String, kind: String) : String = {
    val cut = id.split("[.]", 2)

    val (prefix, radical) =
      if (cut.length == 1) ("", cut(0))
      else (cut(0), cut(1).replace(".", "__"))

    var new_id = radical
    if (namesSet(new_id))
      new_id += "__" + kind
    if (namesSet(new_id))
      new_id = prefix + "__" + new_id
    if (namesSet(new_id))
      error("duplicate name")

    namesSet += new_id
    namesMap += full_name(id, kind) -> new_id
    new_id
  }

  def name_get(a: String, kind: String): String = {
    namesMap get full_name(a, kind) match {
      case None => names_add(a, kind)
      case Some(s) => s
    }
  }

  /* kinds */

  // def kind(a: String, k: Export_Theory.Kind.Value): String = a + "|" + k.toString

  def class_ident(a: String): String = name_get(a, Export_Theory.Kind.CLASS.toString)
  def  type_ident(a: String): String = name_get(a, Export_Theory.Kind.TYPE .toString)
  def const_ident(a: String): String = name_get(a, Export_Theory.Kind.CONST.toString)
  def axiom_ident(a: String): String = name_get(a, Export_Theory.Kind.AXIOM.toString)
  def   thm_ident(a: String): String = name_get(a, Export_Theory.Kind.THM  .toString)
  def   var_ident(a: String): String = name_get(a, "var")

  def proof_ident(serial: Long): String = "proof" + serial

  /* special names */

  val typeId: String = const_ident("Typ")
  val  etaId: String = const_ident("eta")
  val  epsId: String = const_ident("eps")

  val typeT: Syntax.Term = Syntax.Symb(typeId)
  val  etaT: Syntax.Term = Syntax.Symb( etaId)
  val  epsT: Syntax.Term = Syntax.Symb( epsId)
  val  funT: Syntax.Term = Syntax.Symb( type_ident(Pure_Thy.FUN))
  val  impT: Syntax.Term = Syntax.Symb(const_ident(Pure_Thy.IMP))
  val  allT: Syntax.Term = Syntax.Symb(const_ident(Pure_Thy.ALL))


  val typeD: Syntax.Command  = Syntax.Declaration(typeId, Nil, Syntax.TYPE)
  val  etaN: Syntax.Notation = Syntax.Prefix("η", 10)
  val  etaD: Syntax.Command  = Syntax.DefableDecl(etaId, Syntax.arrow(typeT, Syntax.TYPE), inj = true, not = Some(etaN))


  val epsN: Syntax.Notation = Syntax.Prefix("ε", 10)
  val epsD: Syntax.Command =
  {
    val prop = Syntax.Symb(type_ident(Pure_Thy.PROP))
    val eta_prop = Syntax.Appl(etaT, prop)
    Syntax.DefableDecl(epsId, Syntax.arrow(eta_prop, Syntax.TYPE), not = Some(epsN))
  }

  // Integration rewrites

  /* produces "rule f (g &a &b) → f &a ⇒ f &b */
  def rule_distr(etaeps: Syntax.Term, funimp: Syntax.Term): Syntax.Command =
  {
    val a = Syntax.Var(var_ident("a"))
    val b = Syntax.Var(var_ident("b"))
    val etaeps_a = Syntax.Appl(etaeps, a)
    val etaeps_b = Syntax.Appl(etaeps, b)
    Syntax.Rewrite(List(var_ident("a"), var_ident("b")),
      Syntax.Appl(etaeps, Syntax.appls(funimp, List(a, b), List(false, false))),
      Syntax.arrow(etaeps_a, etaeps_b))
  }

  val funR: Syntax.Command = rule_distr(etaT, funT)
  val impR: Syntax.Command = rule_distr(epsT, impT)

  // rule eps ({|Pure.all|const|} &a &b) → ∀ (x : eta &a), eps (&b x)
  val allR: Syntax.Command = {
    val a = Syntax.Var(var_ident("a"))
    val b = Syntax.Var(var_ident("b"))
    val lhs = Syntax.Appl(epsT, Syntax.Appl(Syntax.Appl(allT, a, isImplicit = true), b))
    val eta_a = Syntax.Appl(etaT, a)
    val eps_bx = Syntax.Appl(epsT, Syntax.Appl(b, Syntax.Var(var_ident("x"))))
    Syntax.Rewrite(List(var_ident("a"), var_ident("b")),
      lhs,
      Syntax.Prod(Syntax.BoundArg(Some("x"), Some(eta_a)), eps_bx))
  }
}



object Translate
{
  import Prelude._

  def bound_type_argument(name: String, impl: Boolean = false): Syntax.BoundArg =
    Syntax.BoundArg(Some(var_ident(name)), Some(typeT), impl)

  def bound_term_argument(name: String, ty: Term.Typ, impl: Boolean = false): Syntax.BoundArg = {
    val translated = typ(ty)
    val contracted = eta_contract(translated)
    Syntax.BoundArg(Some(var_ident(name)), Some(eta(contracted)), impl)
  }

  def bound_proof_argument(name: String, tm: Term.Term, bounds: Bounds): Syntax.BoundArg = {
    val translated = term(tm, bounds)
    val contracted = eta_contract(translated)
    Syntax.BoundArg(Some(var_ident(name)), Some(eps(contracted)))
  }

  sealed case class Bounds(
    trm: List[String] = Nil,
    prf: List[String] = Nil)
  {
    def add_trm(tm: String): Bounds = copy(trm = tm :: trm)
    def add_prf(pf: String): Bounds = copy(prf = pf :: prf)

    def get_trm(idx: Int): String = trm(idx)
    def get_prf(idx: Int): String = prf(idx)
  }

  /* types and terms */

  def typ(ty: Term.Typ): Syntax.Typ =
  {
    ty match {
      case Term.TFree(a, _) =>
        Syntax.Var(a)
      case Term.Type(c, args) =>
        Syntax.appls(Syntax.Symb(type_ident(c)), args.map(typ), implArgsMap(type_ident(c)))
      case Term.TVar(xi, _) => error("Illegal schematic type variable " + xi.toString)
    }
  }

  def eta(ty: Syntax.Term): Syntax.Typ =
    Syntax.Appl(etaT, ty)

  def term(tm: Term.Term, bounds: Bounds): Syntax.Term =
  {
    tm match {
      case Term.Const(c, typargs) =>
        Syntax.appls(Syntax.Symb(const_ident(c)), typargs.map(typ), implArgsMap(const_ident(c)))
      case Term.Free(x, _) =>
        Syntax.Var(var_ident(x))
      case Term.Var(xi, _) => error("Illegal schematic variable " + xi.toString)
      case Term.Bound(i) =>
        try Syntax.Var(var_ident(bounds.get_trm(i)))
        catch { case _: IndexOutOfBoundsException => isabelle.error("Loose bound variable " + i) }
      case Term.Abs(x, ty, b) =>
        Syntax.Abst(bound_term_argument(x, ty), term(b, bounds.add_trm(x)))
      case Term.OFCLASS(t, c) =>
        Syntax.Appl(Syntax.Symb(class_ident(c)), typ(t))
      case Term.App(a, b) =>
        Syntax.Appl(term(a, bounds), term(b, bounds))
    }
  }

  def eps(tm: Syntax.Term): Syntax.Term =
    Syntax.Appl(epsT, tm)

  def proof(prf: Term.Proof, bounds: Bounds): Syntax.Term = {
    prf match {
      case Term.PBound(i) =>
        try Syntax.Var(var_ident(bounds.get_prf(i)))
        catch { case _: IndexOutOfBoundsException => isabelle.error("Loose bound variable (proof) " + i) }
      case Term.Abst(x, ty, b) =>
        Syntax.Abst(bound_term_argument(x, ty), proof(b, bounds.add_trm(x)))
      case Term.AbsP(x, prf, b) =>
        Syntax.Abst(
          bound_proof_argument(x, prf, bounds),
          proof(b, bounds.add_prf(x)))
      case Term.Appt(a, b) =>
        Syntax.Appl(proof(a, bounds), term(b, bounds))
      case Term.AppP(a, b) =>
        Syntax.Appl(proof(a, bounds), proof(b, bounds))
      case axm: Term.PAxm =>
        Syntax.appls(Syntax.Symb(axiom_ident(axm.name)), axm.types.map(typ), implArgsMap(axiom_ident(axm.name)))
      case thm: Term.PThm =>
        val head = if (thm.name.nonEmpty) thm_ident(thm.name) else proof_ident(thm.serial)
        Syntax.appls(Syntax.Symb(head), thm.types.map(typ), implArgsMap(head))
      case _ => error("Bad proof term encountered:\n" + prf)
    }
  }

  /* eta contraction */

  def lambda_contains(term: Syntax.Term, ident: Syntax.Ident): Boolean =
    term match {
      case Syntax.TYPE => false
      case Syntax.Symb(id) => id == ident
      case Syntax.Var(id)  => id == ident
      case Syntax.Appl(t1, t2, _) => lambda_contains(t1, ident) || lambda_contains(t2, ident)
      case Syntax.Abst(Syntax.BoundArg(arg, ty, _), t) =>
        !arg.contains(ident) &&
          (ty.fold(false)(lambda_contains(_, ident)) || lambda_contains(t, ident))
      case Syntax.Prod(Syntax.BoundArg(arg, ty, _), t) =>
        !arg.contains(ident) &&
          (ty.fold(false)(lambda_contains(_, ident)) || lambda_contains(t, ident))
    }


  def eta_contract(tm: Syntax.Term) : Syntax.Term =
    tm match {
      case Syntax.Abst(arg @ Syntax.BoundArg(Some(id), _, _), tm2) =>
        eta_contract(tm2) match {
          case Syntax.Appl(tm1, Syntax.Var(id2), _)
            if id == id2 && !lambda_contains(tm1, id) => eta_contract(tm1)
          case tm2 => Syntax.Abst(arg, tm2)
        }

      case Syntax.Prod(arg @ Syntax.BoundArg(Some(id), _, _), tm2) =>
        eta_contract(tm2) match {
          case Syntax.Appl(tm1, Syntax.Var(id2), _)
            if id == id2 && !lambda_contains(tm1, id) => eta_contract(tm1)
          case tm2 => Syntax.Prod(arg, tm2)
        }

      case Syntax.Appl(t1, t2, impl) => Syntax.Appl(eta_contract(t1), eta_contract(t2), impl)
      case _ => tm
    }

  def compatible_bound_args(ba1: Syntax.BoundArg, ba2: Syntax.BoundArg): Boolean =
    (ba1, ba2) match {
      case (Syntax.BoundArg(id1, ty1, impl1), Syntax.BoundArg(id2, ty2, impl2)) =>
        val id = (id1, id2) match {
          case (Some(a), Some(b)) => a == b
          case (None, Some(_)) => false
          case _ => true
        }
        val ty = (ty1, ty2) match {
          case (Some(a), Some(b)) => a == b
          case (None, Some(_)) => false
          case _ => true
        }
        id && ty && impl1 == impl2
    }

  def fetch_head_args(tm: Syntax.Term, ty: Syntax.Term) : (List[Syntax.BoundArg], Syntax.Term, Syntax.Term) =
    (tm, ty) match {
      case (Syntax.Abst(arg @ Syntax.BoundArg(_, arg_ty, false), tm0),
        Syntax.Appl(Syntax.Symb("eta"), Syntax.Appl(Syntax.Appl(Syntax.Symb("fun"), arg_ty2, false), ret_ty, false), false))
      if arg_ty.fold(true)(_ == arg_ty2) => {
        val (lst, tm1, ty1) = fetch_head_args(tm0, ret_ty)
        (arg :: lst, tm1, ty1)
      }
      case (Syntax.Abst(arg @ Syntax.BoundArg(_, arg_ty, false), tm0),
      Syntax.Appl(Syntax.Symb("eps"), Syntax.Appl(Syntax.Appl(Syntax.Symb("imp"), arg_ty2, false), ret_ty, false), false))
        if arg_ty.fold(true)(_ == arg_ty2) => {
        val (lst, tm1, ty1) = fetch_head_args(tm0, ret_ty)
        (arg :: lst, tm1, ty1)
      }
      case (Syntax.Abst(arg, tm0), Syntax.Prod(arg2, ty0))
        if compatible_bound_args(arg, arg2) => {
        val (lst, tm1, ty1) = fetch_head_args(tm0, ty0)
        (arg :: lst, tm1, ty1)
      }

      case _ => (Nil, tm, ty)
  }

  /* notation */

  val notationDict = Map(
    "_" -> "__",
    "\\<Rightarrow>" -> "⇒",
    "\\<equiv>" -> "⩵",
    "\\<Longrightarrow>" -> "⟹",
    "\\<And>" -> "⋀",
    "\\<longrightarrow>" -> "⟶",
    "\\<forall>" -> "∀",
    "\\<exists>" -> "∃",
    "\\<not>" -> "¬",
    "\\<and>" -> "∧",
    "\\<or>" -> "∨",
    "\\<noteq>" ->"≠",
    "\\<le>" -> "≤",
    "\\<bottom>" -> "⊥",
    "\\<top>" -> "⊤",
    "\\<sqinter>" -> "⊓",
    "\\<squnion>" -> "⊔",
    "\\<Sqinter>" -> "⨅",
    "\\<Squnion>" -> "⨆",
    "\\<Inter>" -> "⋂",
    "\\<Union>" -> "⋃",
    "\\<in>" -> "∈",
    "\\<notin>" -> "∉",
    "\\<subset>" -> "⊂",
    "\\<subseteq>" -> "⊆",
    "\\<supset>" -> "⊃",
    "\\<supseteq>" -> "⊇",
    "\\<inter>" -> "∩",
    "\\<union>" -> "∪",
    "\\<circ>" -> "∘",
    "0" -> "'0'",
    "1" -> "'1'"

)

  var notationsSet: Set[String] = Set()

  def notations_get(op: String) : String = {
    var op1 = notationDict getOrElse(op, op)
    while (notationsSet(op1)) {
      op1 = "~"+op1
    }
    notationsSet += op1
    op1
  }

  def notation_decl: Export_Theory.Syntax => Option[Syntax.Notation] = { // TODO: Ugly
    case No_Syntax => None
    case Prefix(op) => Some(Syntax.Prefix(notations_get(op), Syntax.defaultPrefixPriority))
    case Export_Theory.Infix(Export_Theory.Assoc.NO_ASSOC,    op, priority) => Some(Syntax.Infix (notations_get(op), priority + 1))
    case Export_Theory.Infix(Export_Theory.Assoc.LEFT_ASSOC,  op, priority) => Some(Syntax.InfixL(notations_get(op), priority + 1))
    case Export_Theory.Infix(Export_Theory.Assoc.RIGHT_ASSOC, op, priority) => Some(Syntax.InfixR(notations_get(op), priority + 1))
  }

  var implArgsMap: Map[String, List[Boolean]] = Map()

  /* type classes */

  def class_decl(c: String): Syntax.Command = {
    implArgsMap += class_ident(c) -> List(false)
    val eta_prop = Syntax.Appl(etaT, Syntax.Symb(type_ident(Pure_Thy.PROP)))
    Syntax.Declaration(class_ident(c), Nil, Syntax.arrow(typeT, eta_prop))
  }


  /* types */

  def type_decl(c: String, args: List[String], rhs: Option[Term.Typ], not: Export_Theory.Syntax): Syntax.Command = {
    implArgsMap += type_ident(c) -> List.fill(args.length)(false)
    val full_ty = Syntax.arrows(List.fill(args.length)(typeT), typeT)
    rhs match {
      case None =>
        Syntax.Declaration(type_ident(c), Nil, full_ty, notation_decl(not))
      case Some(rhs) => {
        val translated_rhs = typ(rhs)
        val full_tm : Syntax.Term = args.map(bound_type_argument(_)).foldRight(translated_rhs)(Syntax.Prod)
        val (new_args, contracted, ty) = fetch_head_args(eta_contract(full_tm), full_ty)
        Syntax.Definition(type_ident(c), new_args, Some(ty), contracted, notation_decl(not))
      }
    }
  }


  /* consts */

  def type_contains_arg(ty: Term.Typ, arg: String): Boolean =
    ty match {
      case Term.TFree(name, _) => name == arg
      case Term.Type(_, args) => args.exists(type_contains_arg(_, arg))
      case Term.TVar(_, _) => error("False assertion")
    }

  @tailrec
  def type_contains_arg_as_arg(ty: Term.Typ, arg: String): Boolean =
    ty match {
      case Term.Type(Pure_Thy.FUN, List(arg1, arg2)) => type_contains_arg(arg1, arg) || type_contains_arg_as_arg(arg2, arg)
      case Term.Type(_, _) => false  // Can maybe be more intelligent
      case Term.TFree(_, _) => false
      case Term.TVar(_, _) => error("False assertion")
    }

  def const_implicit_args(typargs: List[String], ty: Term.Typ): List[Boolean] = {
    var canStillBeImplicit = true  // No implicit arg after a non-implicit one
    typargs.map(arg => {
      canStillBeImplicit &&= type_contains_arg_as_arg(ty, arg)
      canStillBeImplicit
    })
  }

  def bound_type_arguments(args: List[String], impl: List[Boolean]): List[Syntax.BoundArg] =
    (args, impl) match {
      case (Nil, Nil) => Nil
      case (arg :: args, impl :: impls) => bound_type_argument(arg, impl) :: bound_type_arguments(args, impls)
      case (Nil, _) => isabelle.error("Implicit list too long.")
      case (_, Nil) => isabelle.error("Implicit list too short.")
    }

  def const_decl(c: String, typargs: List[String], ty: Term.Typ, rhs: Option[Term.Term], not: Export_Theory.Syntax): Syntax.Command = {
    implArgsMap += const_ident(c) -> const_implicit_args(typargs, ty)
    val bound_args = bound_type_arguments(typargs, implArgsMap(const_ident(c)))
    val new_ty = eta(eta_contract(typ(ty)))
    rhs match {
      case None =>
        Syntax.Declaration(const_ident(c), bound_args, new_ty, notation_decl(not))
      case Some(rhs) => {
        val translated_rhs = term(rhs, Bounds())
        val full_tm = bound_args.foldRight(translated_rhs)(Syntax.Abst)
        val full_ty = bound_args.foldRight(new_ty)(Syntax.Prod)
        val (new_args, contracted, final_ty) = fetch_head_args(eta_contract(full_tm), full_ty)
        Syntax.Definition(const_ident(c), new_args, Some(final_ty), contracted, notation_decl(not))
      }
    }
  }


  /* theorems and proof terms */

  def stmt_decl(s: String, prop: Export_Theory.Prop, prf_opt: Option[Term.Proof]): Syntax.Command = {
    val args =
      prop.typargs.map(_._1).map(bound_type_argument(_)) :::
      prop.args.map(arg => bound_term_argument(arg._1, arg._2))

    val ty = eps(eta_contract(term(prop.term, Bounds())))

    implArgsMap += s -> List.fill(prop.typargs.length)(false) // Only those are applied immediately

    try prf_opt match {
      case None =>
        Syntax.Declaration(s, args, ty)
      case Some(prf) => {
        val translated_rhs = proof(prf, Bounds())
        val full_prf : Syntax.Term = args.foldRight(translated_rhs)(Syntax.Abst)
        val full_ty  : Syntax.Term = args.foldRight(ty)(Syntax.Prod)
        val (new_args, contracted, final_ty) = fetch_head_args(eta_contract(full_prf), full_ty)
        Syntax.Theorem(s, new_args, final_ty, contracted)
      }
    }
    catch { case ERROR(msg) => error(msg + "\nin " + quote(s)) }
  }
}
