/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.expressions.resolver.rules;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.CompositeType;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.UnresolvedCallExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.singletonList;
import static org.apache.flink.table.expressions.utils.ApiExpressionUtils.valueLiteral;
import static org.apache.flink.table.types.utils.TypeConversions.fromDataTypeToLegacyInfo;
import static org.apache.flink.table.types.utils.TypeConversions.fromLegacyInfoToDataType;

/**
 * Replaces {@link BuiltInFunctionDefinitions#FLATTEN} with resolved calls to {@link BuiltInFunctionDefinitions#GET}
 * for all fields of underlying field of complex type.
 *
 * @see ResolveCallByArgumentsRule
 */
@Internal
final class ResolveFlattenCallRule implements ResolverRule {

	@Override
	public List<Expression> apply(List<Expression> expression, ResolutionContext context) {
		return expression.stream()
			.flatMap(expr -> expr.accept(new FlatteningCallVisitor(context)).stream())
			.collect(Collectors.toList());
	}

	private class FlatteningCallVisitor extends RuleExpressionVisitor<List<Expression>> {

		FlatteningCallVisitor(ResolutionContext context) {
			super(context);
		}

		@Override
		public List<Expression> visit(UnresolvedCallExpression unresolvedCall) {
			if (unresolvedCall.getFunctionDefinition() == BuiltInFunctionDefinitions.FLATTEN) {
				return executeFlatten(unresolvedCall);
			}

			return singletonList(unresolvedCall);
		}

		private List<Expression> executeFlatten(UnresolvedCallExpression unresolvedCall) {
			final Expression composite = unresolvedCall.getChildren().get(0);
			if (!(composite instanceof ResolvedExpression)) {
				throw new TableException("Resolved expression expected for flattening.");
			}
			final ResolvedExpression resolvedComposite = (ResolvedExpression) composite;
			final TypeInformation<?> resultType = fromDataTypeToLegacyInfo(resolvedComposite.getOutputDataType());
			if (resultType instanceof CompositeType) {
				return flattenCompositeType(resolvedComposite, (CompositeType<?>) resultType);
			} else {
				return singletonList(composite);
			}
		}

		private List<Expression> flattenCompositeType(ResolvedExpression resolvedComposite, CompositeType<?> resultType) {
			return IntStream.range(0, resultType.getArity())
				.mapToObj(idx ->
					resolutionContext.postResolutionFactory()
						.get(
							resolvedComposite,
							valueLiteral(resultType.getFieldNames()[idx]),
							fromLegacyInfoToDataType(resultType.getTypeAt(idx)))
				)
				.collect(Collectors.toList());
		}

		@Override
		protected List<Expression> defaultMethod(Expression expression) {
			return singletonList(expression);
		}
	}
}