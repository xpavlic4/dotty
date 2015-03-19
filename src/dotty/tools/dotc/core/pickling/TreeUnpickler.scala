package dotty.tools
package dotc
package core
package pickling

import Contexts._, Symbols._, Types._, Scopes._, SymDenotations._, Names._, NameOps._
import StdNames._, Denotations._, Flags._, Constants._, Annotations._
import util.Positions._
import dotty.tools.dotc.ast.{tpd, Trees, untpd}
import Trees._
import Decorators._
import TastyUnpickler._, TastyBuffer._
import annotation.switch
import scala.collection.{ mutable, immutable }
import typer.Mode
import config.Printers.pickling
import PositionPickler._

/** Unpickler for typed trees
 *  @param reader         the reader from which to unpickle
 *  @param tastyName      the nametable
 */
class TreeUnpickler(reader: TastyReader, tastyName: TastyName.Table) {
  import dotty.tools.dotc.core.pickling.PickleFormat._
  import TastyName._
  import tpd._
  
  private var readPositions = false
  private var totalRange = NoPosition
  private var positions: collection.Map[Addr, Position] = _

  /** Make a subsequent call to `unpickle` return trees with positions
   *  @param totalRange     the range position enclosing all returned trees,
   *                        or NoPosition if positions should not be unpickled
   *  @param positions      a map from tree addresses to their positions relative
   *                        to positions of parent nodes.
   */
  def usePositions(totalRange: Position, positions: collection.Map[Addr, Position]): Unit = {
    readPositions = true
    this.totalRange = totalRange
    this.positions = positions
  }
  
  private val symAtAddr  = new mutable.HashMap[Addr, Symbol]
  private val treeAtAddr = new mutable.HashMap[Addr, Tree]
  private val typeAtAddr = new mutable.HashMap[Addr, Type] // currently populated only for types that are known to be SHAREd.
  private var stubs: Set[Symbol] = Set() 
  
  private var roots: Set[SymDenotation] = null

  /** Enter all toplevel classes and objects into their scopes
   *  @param roots          a set of SymDenotations that should be overwritten by unpickling
   */
  def enterTopLevel(roots: Set[SymDenotation])(implicit ctx: Context): Unit = {
    this.roots = roots
    new TreeReader(reader).fork.indexStats(reader.endAddr)
  }

  /** The unpickled trees */
  def unpickle()(implicit ctx: Context): List[Tree] = {
    assert(roots != null, "unpickle without previous enterTopLevel")
    val stats = new TreeReader(reader)
      .readIndexedStats(NoSymbol, reader.endAddr)(ctx.addMode(Mode.AllowDependentFunctions))
    normalizePos(stats, totalRange)
    stats
  }
    
  def toTermName(tname: TastyName): TermName = tname match {
    case Simple(name) => name
    case Qualified(qual, name) => toTermName(qual) ++ "." ++ toTermName(name)
    case Signed(original, params, result) => toTermName(original)
    case Shadowed(original) => toTermName(original).shadowedName
    case Expanded(original) => ???
    case ModuleClass(original) => toTermName(original).moduleClassName.toTermName
    case SuperAccessor(accessed) => ???
    case DefaultGetter(meth, num) => ???
  }

  def toTermName(ref: NameRef): TermName = toTermName(tastyName(ref))
  def toTypeName(ref: NameRef): TypeName = toTermName(ref).toTypeName

  class Completer(reader: TastyReader) extends LazyType {
    import reader._
    def complete(denot: SymDenotation)(implicit ctx: Context): Unit = {
      treeAtAddr(currentAddr) = new TreeReader(reader).readIndexedDef()
    }
  }

  class TreeReader(val reader: TastyReader) {
    import reader._
        
    def forkAt(start: Addr) = new TreeReader(subReader(start, endAddr))
    def fork = forkAt(currentAddr)
    
    def skipTree(tag: Int): Unit =
      if (tag >= firstLengthTreeTag) goto(readEnd())
      else if (tag >= firstNatASTTreeTag) { readNat(); skipTree() }
      else if (tag >= firstASTTreeTag) skipTree()
      else if (tag >= firstNatTreeTag) readNat()
    def skipTree(): Unit = skipTree(readByte())
    
    def skipParams(): Unit =
      while (nextByte == PARAMS || nextByte == TYPEPARAM) skipTree()

    def readName(): TermName = toTermName(readNameRef())

    def readNameSplitSig()(implicit ctx: Context): Any /* TermName | (TermName, Signature) */ =
      tastyName(readNameRef()) match {
        case Signed(original, params, result) =>
          var sig = Signature(params map toTypeName, toTypeName(result))
          if (sig == Signature.NotAMethod) sig = Signature.NotAMethod
          (toTermName(original), sig)
        case name =>
          toTermName(name)
      }
    
// ------ Reading types -----------------------------------------------------
    
    /** Read names in an interleaved sequence of (parameter) names and types/bounds */
    def readParamNames[N <: Name](end: Addr): List[N] = 
      until(end) { 
        val name = readName().asInstanceOf[N]
        skipTree() 
        name
      }

    /** Read types or bounds in an interleaved sequence of (parameter) names and types/bounds */
    def readParamTypes[T <: Type](end: Addr)(implicit ctx: Context): List[T] = 
      until(end) { readNat(); readType().asInstanceOf[T] }

    /** Read referece to definition and return symbol created at that definition */
    def readSymRef()(implicit ctx: Context): Symbol = {
      val start = currentAddr
      val addr = readAddr()
      symAtAddr get addr match {
        case Some(sym) => sym
        case None =>
          // Create a stub; owner might be wrong but will be overwritten later.
          forkAt(addr).createSymbol()
          val sym = symAtAddr(addr)
          ctx.log(i"forward reference to $sym")
          stubs += sym
          sym
      }
    }

    /** Read a type */
    def readType()(implicit ctx: Context): Type = {
      val start = currentAddr
      val tag = readByte()
      pickling.println(s"reading type ${astTagToString(tag)} at $start")

      def registeringType[T](tp: Type, op: => T): T = {
        typeAtAddr(start) = tp
        op
      }
            
      def readLengthType(): Type = {
        val end = readEnd()
        
        def readNamesSkipParams[N <: Name]: (List[N], TreeReader) = {
          val nameReader = fork
          nameReader.skipTree() // skip result
          val paramReader = nameReader.fork
          (nameReader.readParamNames[N](end), paramReader)
        }
        
        val result =
          (tag: @switch) match {
            case SUPERtype =>
              SuperType(readType(), readType())
            case REFINEDtype =>
              val parent = readType()
              var name: Name = readName()
              if (nextByte == SHARED) {
                val refinedInfo = readType()
                if (refinedInfo.isInstanceOf[TypeBounds]) name = name.toTypeName
                RefinedType(parent, name, refinedInfo)
              }
              else {
                if (nextByte == TYPEBOUNDS || nextByte == TYPEALIAS) name = name.toTypeName
                RefinedType(parent, name, rt => registeringType(rt, readType()))
                  // Note that the lambda is not equivalent to a wildcard closure!
                  // Eta expansion of the latter puts readType() out of the expression.
              }
            case APPLIEDtype =>
              readType().appliedTo(until(end)(readType()))
            case TYPEBOUNDS =>
              TypeBounds(readType(), readType())
            case TYPEALIAS =>
              val alias = readType()
              val variance = 
                if (nextByte == COVARIANT) { readByte(); 1 }
                else if (nextByte == CONTRAVARIANT) { readByte(); -1 }
                else 0
              TypeAlias(alias, variance)
            case ANNOTATED =>
              AnnotatedType(Annotation(readTerm()), readType())
            case ANDtype =>
              AndType(readType(), readType())
            case ORtype =>
              OrType(readType(), readType())
            case BIND =>
              val sym = ctx.newSymbol(ctx.owner, readName().toTypeName, BindDefinedType, readType())
              symAtAddr(start) = sym
              TypeRef.withFixedSym(NoPrefix, sym.name, sym)
            case POLYtype =>
              val (names, paramReader) = readNamesSkipParams[TypeName]
              val result = PolyType(names)(
                pt => registeringType(pt, paramReader.readParamTypes[TypeBounds](end)),
                pt => readType())
              goto(end)
              result
            case METHODtype =>
              val (names, paramReader) = readNamesSkipParams[TermName]
              val result = MethodType(names, paramReader.readParamTypes[Type](end))(
                mt => registeringType(mt, readType()))
              goto(end)
              result
            case PARAMtype =>
              readTypeRef() match {
                case binder: PolyType => PolyParam(binder, readNat())
                case binder: MethodType => MethodParam(binder, readNat())
              }
            case CLASSconst =>
              ConstantType(Constant(readType()))
            case ENUMconst =>
              ConstantType(Constant(readTermRef().termSymbol))
          }
        assert(currentAddr == end, s"$start $currentAddr $end ${astTagToString(tag)}")
        result
      }
      
      def readSimpleType(): Type = (tag: @switch) match {
        case TYPEREFdirect | TERMREFdirect => 
          NamedType.withFixedSym(NoPrefix, readSymRef())
        case TYPEREFsymbol | TERMREFsymbol =>
          val sym = readSymRef()
          val prefix = readType()
          val res = NamedType.withFixedSym(prefix, sym)
          if (prefix.isInstanceOf[ThisType]) res.withDenot(sym.denot) else res
            // without this precaution we get an infinite cycle when unpickling pos/extmethods.scala
            // the problem arises when a self type of a trait is a type parameter of the same trait.
        case TYPEREFpkg =>
          val name = readName()
          val pkg = 
            if (name == nme.ROOT) defn.RootClass
            else if (name == nme.EMPTY_PACKAGE) defn.EmptyPackageClass
            else ctx.requiredPackage(name).moduleClass
          pkg.typeRef
        case TERMREFpkg =>
          val name = readName()
          val pkg = 
            if (name == nme.ROOT) defn.RootPackage
            else if (name == nme.EMPTY_PACKAGE) defn.EmptyPackageVal
            else ctx.requiredPackage(name)
          pkg.termRef
        case TYPEREF =>
          val name =  readName().toTypeName
          TypeRef(readType(), name)
        case TERMREF =>
          readNameSplitSig() match {
            case name: TermName => TermRef.all(readType(), name)
            case (name: TermName, sig: Signature) => TermRef.withSig(readType(), name, sig)
          }
        case THIS =>
          ThisType.raw(readType().asInstanceOf[TypeRef])
        case SKOLEMtype =>
          SkolemType(readTypeRef())
        case SHARED =>
          val ref = readAddr()
          typeAtAddr.getOrElseUpdate(ref, forkAt(ref).readType())
        case UNITconst =>
          ConstantType(Constant(()))
        case TRUEconst =>
          ConstantType(Constant(true))
        case FALSEconst =>
          ConstantType(Constant(false))
        case BYTEconst =>
          ConstantType(Constant(readInt().toByte))
        case SHORTconst =>
          ConstantType(Constant(readInt().toShort))
        case CHARconst =>
          ConstantType(Constant(readNat().toChar))
        case INTconst =>
          ConstantType(Constant(readInt()))
        case LONGconst =>
          ConstantType(Constant(readLongInt()))
        case FLOATconst =>
          ConstantType(Constant(java.lang.Float.intBitsToFloat(readInt())))
        case DOUBLEconst =>
          ConstantType(Constant(java.lang.Double.longBitsToDouble(readLongInt())))
        case STRINGconst =>
          ConstantType(Constant(readName().toString))
        case NULLconst =>
          ConstantType(Constant(null))
        case BYNAMEtype =>
          ExprType(readType())
      }
      
      if (tag < firstLengthTreeTag) readSimpleType() else readLengthType()
    }
    
    def readTypeRef(): Type = 
      typeAtAddr(readAddr())

    def readPath()(implicit ctx: Context): Type = {
      val tp = readType()
      assert(tp.isInstanceOf[SingletonType])
      tp
    }
    
    def readTermRef()(implicit ctx: Context): TermRef = 
      readType().asInstanceOf[TermRef]

// ------ Reading definitions -----------------------------------------------------
    
    private def noRhs(end: Addr): Boolean = 
      currentAddr == end || isModifierTag(nextByte)
      
    private def localContext(owner: Symbol)(implicit ctx: Context) = {
      val lctx = ctx.fresh.setOwner(owner)
      if (owner.isClass) lctx.setScope(owner.unforcedDecls) else lctx.setNewScope
    }
    
    private def normalizeFlags(tag: Int, givenFlags: FlagSet, name: Name, isAbstractType: Boolean, rhsIsEmpty: Boolean)(implicit ctx: Context): FlagSet = {
      val lacksDefinition =
        rhsIsEmpty && !name.isConstructorName && !givenFlags.is(ParamOrAccessor) ||
        isAbstractType
      var flags = givenFlags
      if (lacksDefinition) flags |= Deferred
      if (tag == DEFDEF) flags |= Method
      if (givenFlags is Module)
        flags = flags | (if (tag == VALDEF) ModuleCreationFlags else ModuleClassCreationFlags)
      if (ctx.mode.is(Mode.InSuperCall) && !flags.is(ParamOrAccessor)) flags |= InSuperCall
      if (ctx.owner.isClass) {
        if (tag == TYPEPARAM) flags |= Param | ExpandedName // TODO check name to determine ExpandedName
        else if (tag == PARAM) flags |= ParamAccessor
      }
      else if (isParamTag(tag)) flags |= Param
      flags
    }

    /** Create symbol of definition node and enter in symAtAddr map 
     *  @return  true iff the definition does not contain initialization code
     */
    def createSymbol()(implicit ctx: Context): Boolean = {
      val start = currentAddr
      val tag = readByte()
      val end = readEnd()
      val name = if (tag == TYPEDEF || tag == TYPEPARAM) readName().toTypeName else readName()
      skipParams()
      val isAbstractType = nextByte == TYPEBOUNDS
      val isClass = nextByte == TEMPLATE
      val templateStart = currentAddr
      skipTree() // tpt
      val rhsIsEmpty = noRhs(end)
      if (!rhsIsEmpty) skipTree()
      val (givenFlags, annots, privateWithin) = readModifiers(end)
      pickling.println(i"creating symbol $name at $start with flags $givenFlags")
      val flags = normalizeFlags(tag, givenFlags, name, isAbstractType, rhsIsEmpty)
      val nameMatches = (_: Denotation).symbol.name == name
      val prevDenot: SymDenotation = 
        if (ctx.owner.is(Package)) ctx.effectiveScope.lookup(name)
        else NoDenotation // TODO check for double reads
      var completer: LazyType = 
        if (prevDenot.exists) new Completer(subReader(start, end)) with SymbolLoaders.SecondCompleter
        else new Completer(subReader(start, end))
      if (flags is Module) completer = ctx.adjustModuleCompleter(completer, name)
      val sym =
        if (roots contains prevDenot) {
          pickling.println(i"overwriting ${prevDenot.symbol} # ${prevDenot.hashCode}")
          prevDenot.info = completer
          prevDenot.flags = flags &~ Touched // allow one more completion
          prevDenot.privateWithin = privateWithin
          prevDenot.symbol
        } else if (isClass)
          ctx.newClassSymbol(ctx.owner, name.asTypeName, flags, completer, privateWithin, coord = start.index)
        else {
          val sym = symAtAddr.get(start) match {
            case Some(preExisting) =>
              assert(stubs contains preExisting)
              stubs -= preExisting
              preExisting
            case none =>
              ctx.newNakedSymbol(start.index)
          }
          val denot = ctx.SymDenotation(symbol = sym, owner = ctx.owner, name, flags, completer, privateWithin)
          sym.denot = denot
          sym
        } // TODO set position
      sym.annotations = annots
      ctx.enter(sym)
      symAtAddr(start) = sym
      if (isClass) {
        completer.withDecls(newScope)
        forkAt(templateStart).indexTemplateParams()(localContext(sym))
      }
      tag != VALDEF || rhsIsEmpty
    }

    /** Read modifier list into triplet of flags, annotations and a privateWithin 
     *  boindary symbol.
     */
    def readModifiers(end: Addr)(implicit ctx: Context): (FlagSet, List[Annotation], Symbol) = {
      var flags: FlagSet = EmptyFlags
      var annots = new mutable.ListBuffer[Annotation]
      var privateWithin: Symbol = NoSymbol
      while (currentAddr.index != end.index) {
        def addFlag(flag: FlagSet) = {
          flags |= flag
          readByte()
        }
        nextByte match {
          case PRIVATE => addFlag(Private)
          case INTERNAL => ??? // addFlag(Internal)
          case PROTECTED => addFlag(Protected)
          case ABSTRACT => addFlag(Abstract)
          case FINAL => addFlag(Final)
          case SEALED => addFlag(Sealed)
          case CASE => addFlag(Case)
          case IMPLICIT => addFlag(Implicit)
          case LAZY => addFlag(Lazy)
          case OVERRIDE => addFlag(Override)
          case INLINE => addFlag(Inline)
          case ABSOVERRIDE => addFlag(AbsOverride)
          case STATIC => addFlag(JavaStatic)
          case OBJECT => addFlag(Module)
          case TRAIT => addFlag(Trait)
          case LOCAL => addFlag(Local)
          case SYNTHETIC => addFlag(Synthetic)
          case ARTIFACT => addFlag(Artifact)
          case MUTABLE => addFlag(Mutable)
          case LABEL => addFlag(Label)
          case FIELDaccessor => addFlag(Accessor)
          case CASEaccessor => addFlag(CaseAccessor)
          case COVARIANT => addFlag(Covariant)
          case CONTRAVARIANT => addFlag(Contravariant)
          case SCALA2X => addFlag(Scala2x)
          case DEFAULTparameterized => addFlag(DefaultParameterized)
          case INSUPERCALL => addFlag(InSuperCall)
          case PRIVATEqualified => 
            readByte()
            privateWithin = readType().typeSymbol
          case PROTECTEDqualified =>
            addFlag(Protected)
            privateWithin = readType().typeSymbol
          case ANNOTATION =>
            readByte()
            val end = readEnd()
            val sym = readType().typeSymbol
            val lazyAnnotTree = readLater(end, rdr => ctx => rdr.readTerm()(ctx))
            annots += Annotation.deferred(sym, _ => lazyAnnotTree.complete)
          case _ =>
            assert(false, s"illegal modifier tag at $currentAddr")
        }
      }
      (flags, annots.toList, privateWithin)
    }
    
    /** Create symbols for a definitions in statement sequence between
     *  current address and `end`.
     *  @return  true iff none of the statements contains initialization code
     */
    def indexStats(end: Addr)(implicit ctx: Context): Boolean = {
      val noInitss = 
        until(end) { 
          nextByte match {
            case VALDEF | DEFDEF | TYPEDEF | TYPEPARAM | PARAM => 
              createSymbol() 
            case IMPORT => 
              skipTree()
              true
            case PACKAGE => 
              processPackage { (pid, end) => implicit ctx => indexStats(end) }
            case _ => 
              skipTree()
              false
          }
        }
      noInitss.forall(_ == true)
    }

    /** Process package with given operation `op`. The operation takes as arguments
     *   - a `RefTree` representing the `pid` of the package,
     *   - an end address,
     *   - a context which has the processd package as owner
     */
    def processPackage[T](op: (RefTree, Addr) => Context => T)(implicit ctx: Context): T = {
      readByte()
      val end = readEnd()
      val pid = ref(readTermRef()).asInstanceOf[RefTree]
      op(pid, end)(localContext(pid.symbol.moduleClass))
    }
    
    /** Create symbols the longest consecutive sequence of parameters with given
     *  `tag starting at current address.
     */
    def indexParams(tag: Int)(implicit ctx: Context) =
      while (nextByte == tag) createSymbol()
      
    /** Create symbols for all type and value parameters of template starting
     *  at current address.
     */
    def indexTemplateParams()(implicit ctx: Context) = {
      assert(readByte() == TEMPLATE)
      readEnd()
      indexParams(TYPEPARAM)
      indexParams(PARAM)
    }

    /** If definition was already read by a completer, return the previously read tree
     *  or else read definition. 
     */
    def readIndexedDef()(implicit ctx: Context): Tree = treeAtAddr.remove(currentAddr) match {
      case Some(tree) => skipTree(); tree
      case none => readNewDef()
    }
    
    private def readNewDef()(implicit ctx: Context): Tree = {
      val start = currentAddr
      val sym = symAtAddr(start)
      val tag = readByte()
      val end = readEnd()

      def readParams[T <: MemberDef](tag: Int)(implicit ctx: Context): List[T] = {
        fork.indexParams(tag)
        readIndexedParams(tag)
      }
    
      def readParamss(implicit ctx: Context): List[List[ValDef]] = {
        collectWhile(nextByte == PARAMS) {
          readByte()
          readEnd()
          readParams[ValDef](PARAM)
        }
      }
      
      def readRhs(implicit ctx: Context) = 
        if (noRhs(end)) EmptyTree
        else readLater(end, rdr => ctx => rdr.readTerm()(ctx))

      def localCtx = localContext(sym)
      
      def DefDef(tparams: List[TypeDef], vparamss: List[List[ValDef]], tpt: Tree) = 
         ta.assignType(
            untpd.DefDef(
              sym.name.asTermName, tparams, vparamss, tpt, readRhs(localCtx)),
            sym)
      
      def ta =  ctx.typeAssigner

      val name = readName()
      pickling.println(s"reading def of $name at $start")
      val tree: MemberDef = tag match {
        case DEFDEF =>
          val tparams = readParams[TypeDef](TYPEPARAM)(localCtx)
          val vparamss = readParamss(localCtx)
          val tpt = readTpt()
          val typeParams = tparams.map(_.symbol)
          val valueParamss = ctx.normalizeIfConstructor(
              vparamss.nestedMap(_.symbol), name == nme.CONSTRUCTOR)
          val resType = ctx.effectiveResultType(sym, typeParams, tpt.tpe)
          sym.info = ctx.methodType(typeParams, valueParamss, resType)
          DefDef(tparams, vparamss, tpt)
        case VALDEF =>
          sym.info = readType()
          ValDef(sym.asTerm, readRhs(localCtx))
        case TYPEDEF | TYPEPARAM =>
          if (sym.isClass) {
            val cls = sym.asClass
            def setClsInfo(parents: List[TypeRef], selfType: Type) = 
              cls.info = ClassInfo(cls.owner.thisType, cls, parents, cls.unforcedDecls, selfType)
            setClsInfo(Nil, NoType)
            val impl = readTemplate(localCtx)
            setClsInfo(
              ctx.normalizeToClassRefs(impl.parents.map(_.tpe), cls, cls.unforcedDecls),
              if (impl.self.isEmpty) NoType else impl.self.tpt.tpe)
            ta.assignType(untpd.TypeDef(sym.name.asTypeName, impl), sym)
          }
          else {
            sym.info = readType()
            TypeDef(sym.asType)
          }
        case PARAM =>
          val info = readType()
          if (noRhs(end)) {
            sym.info = info
            ValDef(sym.asTerm)
          }
          else {
            sym.setFlag(Method)
            sym.info = ExprType(info)
            pickling.println(i"reading param alias $name -> $currentAddr")
            DefDef(Nil, Nil, TypeTree(info))
          }
      }
      val mods = 
        if (sym.annotations.isEmpty) EmptyModifiers
        else Modifiers(annotations = sym.annotations.map(_.tree))
      tree.withMods(mods) // record annotations in tree so that tree positions can be filled in.
      goto(end)
      setPos(start, tree)
    }
    
    private def readTemplate(implicit ctx: Context): Template = {
      val start = currentAddr
      val cls = ctx.owner.asClass
      val localDummy = ctx.newLocalDummy(cls)
      assert(readByte() == TEMPLATE)
      val end = readEnd()
      val tparams = readIndexedParams[TypeDef](TYPEPARAM)
      val vparams = readIndexedParams[ValDef](PARAM)
      val parents = collectWhile(nextByte != SELFDEF && nextByte != DEFDEF) {
        nextByte match {
          case APPLY | TYPEAPPLY => readTerm()
          case _ => readTpt()
        }
      }
      val self = 
        if (nextByte == SELFDEF) {
          readByte()
          untpd.ValDef(readName(), readTpt(), EmptyTree).withType(NoType)
        }
        else EmptyValDef
      val noInits = fork.indexStats(end)
      if (noInits) cls.setFlag(NoInits)
      val constr = readIndexedDef().asInstanceOf[DefDef]

      def mergeTypeParamsAndAliases(tparams: List[TypeDef], stats: List[Tree]): (List[Tree], List[Tree]) = 
        (tparams, stats) match {
          case (tparam :: tparams1, (alias: TypeDef) :: stats1) 
          if tparam.name == alias.name.expandedName(cls) =>
            val (tas, stats2) = mergeTypeParamsAndAliases(tparams1, stats1)
            (tparam :: alias :: tas, stats2)
          case _ =>
            (tparams, stats)
        }            
    
      val lazyStats = readLater(end, rdr => implicit ctx => {
        val stats0 = rdr.readIndexedStats(localDummy, end)
        val (tparamsAndAliases, stats) = mergeTypeParamsAndAliases(tparams, stats0)
        tparamsAndAliases ++ vparams ++ stats
      })
      setPos(start,
        untpd.Template(constr, parents, self, lazyStats)
          .withType(localDummy.nonMemberTermRef))
    }
    
    def readIndexedStat(exprOwner: Symbol)(implicit ctx: Context): Tree = nextByte match {
      case TYPEDEF | VALDEF | DEFDEF | IMPORT => 
        readIndexedDef()
      case IMPORT => 
        ???
      case PACKAGE =>
        val start = currentAddr
        processPackage { (pid, end) => implicit ctx =>
          setPos(start, PackageDef(pid, readIndexedStats(exprOwner, end)(ctx)))
        }
      case _ => 
        readTerm()(ctx.withOwner(exprOwner))
    }
    
    def readIndexedStats(exprOwner: Symbol, end: Addr)(implicit ctx: Context): List[Tree] =
      until(end)(readIndexedStat(exprOwner))
    
    def readStats(exprOwner: Symbol, end: Addr)(implicit ctx: Context): List[Tree] = {
      fork.indexStats(end)
      readIndexedStats(exprOwner, end)
    } 
    
    def readIndexedParams[T <: MemberDef](tag: Int)(implicit ctx: Context): List[T] =
      collectWhile(nextByte == tag) { readIndexedDef().asInstanceOf[T] }

// ------ Reading terms -----------------------------------------------------

    def readTerm()(implicit ctx: Context): Tree = {
      val start = currentAddr
      val tag = readByte()
      pickling.println(s"reading term ${astTagToString(tag)} at $start")

      def readPathTerm(): Tree = {
        goto(start)
        readPath() match {
          case path: TermRef => ref(path)
          case path: ThisType => This(path.cls)
          case path: ConstantType => Literal(path.value)
        }
      }

      def readSimpleTerm(): Tree = tag match {
        case IDENT =>
          untpd.Ident(readName()).withType(readType())
        case SELECT =>
          def readQual(name: Name) = {
            val localCtx = 
              if (name == nme.CONSTRUCTOR) ctx.fresh.addMode(Mode.InSuperCall) else ctx
            readTerm()(localCtx)
          }
          def readRest(name: Name, sig: Signature) = {
            val unshadowed = if (name.isShadowedName) name.revertShadowed else name
            val qual = readQual(name)
            untpd.Select(qual, unshadowed)
              .withType(TermRef.withSig(qual.tpe.widenIfUnstable, name.asTermName, sig))
          }
          readNameSplitSig match {
            case name: Name => readRest(name, Signature.NotAMethod)
            case (name: Name, sig: Signature) => readRest(name, sig)
          }
          
        case NEW =>
          New(readTpt())
        case _ =>
          readPathTerm()
      }

      def readLengthTerm(): Tree = {
        val end = readEnd()

        val result =
          (tag: @switch) match {
            case SUPER => 
              val qual = readTerm()
              val mixClass = ifBefore(end)(readType().typeSymbol, NoSymbol)
              val mixName = if (mixClass.exists) mixClass.name.asTypeName else tpnme.EMPTY
              tpd.Super(qual, mixName, ctx.mode.is(Mode.InSuperCall), mixClass)
            case APPLY => 
              val fn = readTerm()
              val isJava = fn.tpe.isInstanceOf[JavaMethodType]
              def readArg() = readTerm() match {
                case SeqLiteral(elems) if isJava => JavaSeqLiteral(elems)
                case arg => arg
              }
              tpd.Apply(fn, until(end)(readArg()))
            case TYPEAPPLY =>
              tpd.TypeApply(readTerm(), until(end)(readTpt()))
            case PAIR =>
              Pair(readTerm(), readTerm())
            case TYPED =>
              Typed(readTerm(), readTpt())
            case NAMEDARG =>
              NamedArg(readName(), readTerm())
            case ASSIGN =>
              Assign(readTerm(), readTerm())
            case BLOCK =>
              val exprReader = fork
              skipTree()
              val localCtx = ctx.fresh.setNewScope
              val stats = readStats(ctx.owner, end)(localCtx)
              val expr = exprReader.readTerm()(localCtx)
              Block(stats, expr)
            case IF =>
              If(readTerm(), readTerm(), readTerm())
            case LAMBDA =>
              val meth = readTerm()
              val tpt = ifBefore(end)(readTpt(), EmptyTree)
              Closure(Nil, meth, tpt)
            case MATCH =>
              Match(readTerm(), readCases(end))
            case RETURN =>
              val from = readSymRef()
              val expr = ifBefore(end)(readTerm(), EmptyTree)
              Return(expr, Ident(from.termRef))
            case TRY =>
              Try(readTerm(), readCases(end), ifBefore(end)(readTerm(), EmptyTree))
            case REPEATED =>
              SeqLiteral(until(end)(readTerm()))
            case BIND =>
              val name = readName()
              val info = readType()
              val sym = ctx.newSymbol(ctx.owner, name, EmptyFlags, info)
              symAtAddr(start) = sym
              Bind(sym, readTerm())
            case ALTERNATIVE =>
              Alternative(until(end)(readTerm()))
            case UNAPPLY =>
              val fn = readTerm()
              val implicitArgs = 
                collectWhile(nextByte == IMPLICITarg) {
                  readByte()
                  readTerm()
                }
              val patType = readType()
              val argPats = until(end)(readTerm())
              UnApply(fn, implicitArgs, argPats, patType)
            case _ =>
              readPathTerm()
          }
        assert(currentAddr == end, s"$start $currentAddr $end ${astTagToString(tag)}")
        result
      }
      
      val tree = if (tag < firstLengthTreeTag) readSimpleTerm() else readLengthTerm()
      tree.overwriteType(tree.tpe.simplified)
      setPos(start, tree)
    }
    
    def readTpt()(implicit ctx: Context) = {
      val start = currentAddr
      val tp = readType()
      if (tp.exists) setPos(start, TypeTree(tp)) else EmptyTree
    }

    def readCases(end: Addr)(implicit ctx: Context): List[CaseDef] = 
      collectWhile(nextByte == CASEDEF && currentAddr != end) { readCase()(ctx.fresh.setNewScope) }

    def readCase()(implicit ctx: Context): CaseDef = {
      val start = currentAddr
      readByte()
      val end = readEnd()
      val pat = readTerm()
      val rhs = readTerm()
      val guard = ifBefore(end)(readTerm(), EmptyTree)
      setPos(start, CaseDef(pat, guard, rhs))
    }
    
    def readLater[T <: AnyRef](end: Addr, op: TreeReader => Context => T): Trees.Lazy[T] = {
      val localReader = fork
      goto(end)
      new LazyReader(localReader, op)
    }
    
// ------ Hooks for positions ------------------------------------------------
          
    /** Record address from which tree was created as a temporary position in the tree. 
     *  The temporary position contains deltas relative to the position of the (as yet unknown)
     *  parent node. It is marked as a non-synthetic source position. 
     */
    def setPos[T <: Tree](addr: Addr, tree: T): T = {
      if (readPositions)
        tree.setPosUnchecked(positions.getOrElse(addr, Position(0, 0, 0)))
      tree
    }
  }
  
  private def setNormalized(tree: Tree, parentPos: Position): Unit = {
    assert(tree.pos.exists)
    val absPos = Position(parentPos.start + offsetToInt(tree.pos.start), parentPos.end - tree.pos.end)
    tree.setPosUnchecked(absPos)
  }
  
  def normalizePos(x: Any, parentPos: Position)(implicit ctx: Context): Unit =
    traverse(x, parentPos, setNormalized)

  class LazyReader[T <: AnyRef](reader: TreeReader, op: TreeReader => Context => T) extends Trees.Lazy[T] with DeferredPosition {
    def complete(implicit ctx: Context): T = {
      pickling.println(i"starting to read at ${reader.reader.currentAddr}")
      val res = op(reader)(ctx.addMode(Mode.AllowDependentFunctions))
      normalizePos(res, parentPos)
      res
    }  
  }
  
  class LazyAnnotationReader(sym: Symbol, reader: TreeReader) 
      extends LazyAnnotation(sym) with DeferredPosition {
    def complete(implicit ctx: Context) = {
      val res = reader.readTerm()
      normalizePos(res, parentPos)
      res
    }
  }
}